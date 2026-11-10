<script setup>
// CRUD de promociones -> tabla `promociones` + `promocion_productos`. Calca AdminServiciosView.vue
// (mismo patrón de stats/filtros/tabla/paginación/modal), adaptado a los 3 tipos de promoción
// (descuento/combo/servicio) - ver PromocionFormModal.vue para el formulario en sí.
import { ref, computed, watch } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useToast } from '../../composables/useToast'
import { formatCOP, formatDate, fechaLocalISO } from '../../composables/useFormat'
import PromocionFormModal from '../../components/admin/PromocionFormModal.vue'

const catalog = useCatalogStore()
const { showToast } = useToast()

const busqueda = ref('')
const filtroTipo = ref('')
const filtroEstado = ref('')
const paginaActual = ref(1)
const POR_PAGINA = 8

const TIPO_LABEL = { descuento: 'Descuento', combo: 'Combo', servicio: 'Servicio' }
const TIPO_ICON = { descuento: 'ri-price-tag-3-line', combo: 'ri-gift-line', servicio: 'ri-tools-line' }

// Mismo cálculo de vigencia que el sitio público (catalog.esPromoVigente) - "Programada"/"Vencida"
// son estados calculados aquí para el admin, igual que en AdminReportesView.vue, no existen en el
// ENUM real de la BD (solo `activo` sí).
function estadoPromo(promo) {
  if (!promo.activo) return { label: 'Inactiva', clase: 'badge-gray' }
  const hoy = fechaLocalISO()
  if (promo.fecha_inicio && hoy < promo.fecha_inicio) return { label: 'Programada', clase: 'badge-blue' }
  if (promo.fecha_fin && hoy > promo.fecha_fin) return { label: 'Vencida', clase: 'badge-gray' }
  return { label: 'Vigente', clase: 'badge-green' }
}

function aplicaA(promo) {
  if (promo.productos?.length) {
    return promo.productos.map((id) => catalog.getProductById(id)?.nombre).filter(Boolean).join(' + ') || '—'
  }
  if (promo.id_servicio) return catalog.getServiceById(promo.id_servicio)?.nombre_servicio || '—'
  return '—'
}
function valorPromo(promo) {
  if (promo.precio_especial) return formatCOP(promo.precio_especial)
  if (promo.descuento_porcentaje) return `${promo.descuento_porcentaje}% OFF`
  return '—'
}
function imagenPromo(promo) {
  if (promo.imagen_url) return promo.imagen_url
  const primerProducto = promo.productos?.[0] ? catalog.getProductById(promo.productos[0]) : null
  return primerProducto?.imagen_url || ''
}
function vigenciaTexto(promo) {
  if (!promo.fecha_inicio && !promo.fecha_fin) return 'Sin fecha límite'
  if (promo.fecha_inicio && promo.fecha_fin) return `${formatDate(promo.fecha_inicio)} – ${formatDate(promo.fecha_fin)}`
  if (promo.fecha_fin) return `Hasta ${formatDate(promo.fecha_fin)}`
  return `Desde ${formatDate(promo.fecha_inicio)}`
}

const promocionesConEstado = computed(() => catalog.promociones.map((p) => ({ ...p, estado: estadoPromo(p) })))

const promocionesFiltradas = computed(() => {
  const term = busqueda.value.trim().toLowerCase()
  return promocionesConEstado.value
    .filter((p) => {
      const coincideTerm = !term || p.titulo.toLowerCase().includes(term)
      const coincideTipo = !filtroTipo.value || p.tipo === filtroTipo.value
      const coincideEstado = !filtroEstado.value || p.estado.label.toLowerCase() === filtroEstado.value
      return coincideTerm && coincideTipo && coincideEstado
    })
    .sort((a, b) => b.id_promocion - a.id_promocion)
})

const totalPaginas = computed(() => Math.max(1, Math.ceil(promocionesFiltradas.value.length / POR_PAGINA)))
const promocionesPagina = computed(() => {
  const inicio = (paginaActual.value - 1) * POR_PAGINA
  return promocionesFiltradas.value.slice(inicio, inicio + POR_PAGINA)
})
watch([busqueda, filtroTipo, filtroEstado], () => { paginaActual.value = 1 })
watch(totalPaginas, (nuevo) => { if (paginaActual.value > nuevo) paginaActual.value = nuevo })

function limpiarFiltros() {
  busqueda.value = ''
  filtroTipo.value = ''
  filtroEstado.value = ''
}
function toggleFiltroEstado(valor) {
  filtroEstado.value = filtroEstado.value === valor ? '' : valor
}
function irAPagina(n) {
  paginaActual.value = Math.min(Math.max(1, n), totalPaginas.value)
}

const totalPromociones = computed(() => catalog.promociones.length)
const totalVigentes = computed(() => promocionesConEstado.value.filter((p) => p.estado.label === 'Vigente').length)
const pctVigentes = computed(() => (totalPromociones.value ? Math.round((totalVigentes.value / totalPromociones.value) * 100) : 0))
const totalDestacadas = computed(() => catalog.promociones.filter((p) => p.destacado).length)

const mostrarForm = ref(false)
const promocionEditando = ref(null)
function abrirCrear() {
  promocionEditando.value = null
  mostrarForm.value = true
}
function abrirEditar(p) {
  promocionEditando.value = p
  mostrarForm.value = true
}
function onGuardado({ esNuevo }) {
  showToast(esNuevo ? '¡Promoción creada correctamente!' : '¡Promoción actualizada correctamente!', 'success')
}

const cambiandoDestacado = ref(null)
async function toggleDestacado(promo) {
  cambiandoDestacado.value = promo.id_promocion
  try {
    await catalog.actualizarPromocion(promo.id_promocion, { ...promo, destacado: !promo.destacado })
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo actualizar la promoción.', 'danger')
  }
  cambiandoDestacado.value = null
}

const promocionAEliminar = ref(null)
async function eliminarPromocion() {
  const promo = promocionAEliminar.value
  try {
    await catalog.eliminarPromocion(promo.id_promocion)
    showToast('Promoción eliminada.', 'success')
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo eliminar la promoción. Intenta de nuevo.', 'danger')
  }
  promocionAEliminar.value = null
}
</script>

<template>
  <div class="admin-page-header">
    <div>
      <h1>Gestión de Promociones</h1>
      <p>Crea descuentos, combos y ofertas de servicios — decide cuáles se destacan en el inicio.</p>
    </div>
    <button type="button" class="btn btn-primary" @click="abrirCrear">
      <i class="ri-add-line"></i> Nueva Promoción
    </button>
  </div>

  <div class="prod-stats-grid">
    <button type="button" class="prod-stat" :class="{ active: !filtroEstado }" @click="filtroEstado = ''">
      <div class="prod-stat-label"><i class="ri-price-tag-3-line"></i> Total promociones</div>
      <strong class="prod-stat-value">{{ totalPromociones }}</strong>
    </button>
    <button type="button" class="prod-stat" :class="{ active: filtroEstado === 'vigente' }" @click="toggleFiltroEstado('vigente')">
      <div class="prod-stat-label"><i class="ri-flashlight-line"></i> Vigentes</div>
      <div class="prod-stat-value-row">
        <strong class="prod-stat-value">{{ totalVigentes }}</strong>
        <span class="prod-stat-note ok">{{ pctVigentes }}% del total</span>
      </div>
    </button>
    <button type="button" class="prod-stat">
      <div class="prod-stat-label"><i class="ri-star-line"></i> Destacadas en inicio</div>
      <strong class="prod-stat-value">{{ totalDestacadas }}</strong>
    </button>
  </div>

  <div class="admin-filters-bar">
    <div class="search-input-wrap">
      <input v-model="busqueda" type="search" class="form-control" placeholder="Buscar por título..." />
    </div>
    <select v-model="filtroTipo">
      <option value="">Tipo de promoción</option>
      <option value="descuento">Descuento</option>
      <option value="combo">Combo</option>
      <option value="servicio">Servicio</option>
    </select>
    <select v-model="filtroEstado">
      <option value="">Estado</option>
      <option value="vigente">Vigente</option>
      <option value="programada">Programada</option>
      <option value="vencida">Vencida</option>
      <option value="inactiva">Inactiva</option>
    </select>
    <button type="button" class="btn btn-outline-red btn-sm" @click="limpiarFiltros">
      <i class="ri-filter-off-line"></i> Limpiar filtros
    </button>
  </div>

  <div class="admin-card">
    <div class="admin-card-header">
      <h2>Lista de promociones</h2>
      <span>{{ promocionesFiltradas.length }} registros encontrados</span>
    </div>

    <div v-if="!promocionesPagina.length" class="admin-empty">
      <i class="ri-price-tag-3-line"></i>
      <p>No hay promociones que coincidan con estos filtros.</p>
    </div>

    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>Promoción</th>
            <th>Tipo</th>
            <th>Aplica a</th>
            <th>Valor</th>
            <th>Vigencia</th>
            <th>Inicio</th>
            <th>Estado</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in promocionesPagina" :key="p.id_promocion">
            <td>
              <div class="admin-prod-cell">
                <img v-if="imagenPromo(p)" :src="imagenPromo(p)" :alt="p.titulo" class="admin-prod-thumb" />
                <div v-else class="admin-prod-thumb admin-prod-thumb-empty"><i class="ri-image-line"></i></div>
                <div class="admin-prod-info">
                  <strong :title="p.titulo">{{ p.titulo }}</strong>
                  <span>{{ p.descripcion || 'Sin descripción' }}</span>
                </div>
              </div>
            </td>
            <td><span class="badge badge-gray"><i :class="TIPO_ICON[p.tipo]"></i>&nbsp; {{ TIPO_LABEL[p.tipo] || p.tipo }}</span></td>
            <td>{{ aplicaA(p) }}</td>
            <td>{{ valorPromo(p) }}</td>
            <td>{{ vigenciaTexto(p) }}</td>
            <td>
              <button
                type="button" class="admin-action-btn"
                :title="p.destacado ? 'Quitar del inicio' : 'Destacar en el inicio'"
                :disabled="cambiandoDestacado === p.id_promocion"
                @click="toggleDestacado(p)"
              >
                <i :class="p.destacado ? 'ri-star-fill' : 'ri-star-line'" :style="p.destacado ? 'color:var(--accent);' : ''"></i>
              </button>
            </td>
            <td><span class="badge" :class="p.estado.clase">{{ p.estado.label }}</span></td>
            <td>
              <div class="admin-actions-cell">
                <button type="button" class="admin-action-btn" title="Editar" @click="abrirEditar(p)"><i class="ri-edit-line"></i></button>
                <button type="button" class="admin-action-btn danger" title="Eliminar" @click="promocionAEliminar = p"><i class="ri-delete-bin-line"></i></button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="totalPaginas > 1" class="admin-pagination">
      <button type="button" class="admin-page-btn" :disabled="paginaActual === 1" @click="irAPagina(paginaActual - 1)"><i class="ri-arrow-left-s-line"></i></button>
      <button
        v-for="n in totalPaginas"
        :key="n"
        type="button"
        class="admin-page-btn"
        :class="{ active: n === paginaActual }"
        @click="irAPagina(n)"
      >{{ n }}</button>
      <button type="button" class="admin-page-btn" :disabled="paginaActual === totalPaginas" @click="irAPagina(paginaActual + 1)"><i class="ri-arrow-right-s-line"></i></button>
    </div>
  </div>

  <PromocionFormModal
    :mostrar="mostrarForm"
    :promocion="promocionEditando"
    @cerrar="mostrarForm = false"
    @guardado="onGuardado"
  />

  <Transition name="confirm-modal-fade">
    <div v-if="promocionAEliminar" class="confirm-modal-overlay" @click.self="promocionAEliminar = null">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="promocionAEliminar = null"><i class="ri-close-line"></i></button>
          <div class="confirm-modal-icon" style="background:rgba(192,57,43,0.1);color:var(--primary);"><i class="ri-delete-bin-line"></i></div>
          <h3 class="confirm-modal-title">¿Eliminar esta promoción?</h3>
          <p class="confirm-modal-text">
            Vas a eliminar <strong>{{ promocionAEliminar?.titulo }}</strong>. Esta acción no se puede deshacer.
          </p>
          <div class="confirm-modal-actions">
            <button class="btn btn-primary btn-lg btn-block" style="background:var(--danger);" @click="eliminarPromocion">
              <i class="ri-delete-bin-line"></i> Sí, eliminar
            </button>
            <button class="btn btn-outline-red btn-block" @click="promocionAEliminar = null">Cancelar</button>
          </div>
        </div>
      </Transition>
    </div>
  </Transition>
</template>

<style scoped>
.prod-stats-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; margin-bottom: 22px; }
.prod-stat {
  display: block; width: 100%; text-align: left; background: white; border: 1px solid var(--border);
  border-radius: var(--radius-lg); padding: 20px 22px; cursor: pointer; transition: var(--transition); font-family: inherit;
}
.prod-stat:hover { border-color: var(--primary); box-shadow: var(--shadow); }
.prod-stat.active { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(192,57,43,0.1); }
.prod-stat-label {
  display: flex; align-items: center; gap: 7px; font-family: var(--font-main); font-weight: 700;
  font-size: 0.76rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.4px; margin-bottom: 12px;
}
.prod-stat-label i { font-size: 0.95rem; color: var(--text-light); }
.prod-stat-value-row { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.prod-stat-value { font-family: var(--font-main); font-weight: 800; font-size: 1.55rem; color: var(--secondary); }
.prod-stat-note { font-size: 0.78rem; color: var(--text-muted); }
.prod-stat-note.ok { color: var(--success); font-weight: 600; }

.admin-prod-thumb-empty { display: flex; align-items: center; justify-content: center; color: var(--text-muted); font-size: 1.1rem; background: var(--off-white); }

@media (max-width: 1100px) {
  .prod-stats-grid { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 640px) {
  .prod-stats-grid { grid-template-columns: 1fr; }
}
</style>
