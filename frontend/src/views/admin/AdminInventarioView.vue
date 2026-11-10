<script setup>
// Panel de Inventario -> tablas `inventario` + `movimientos_inventario` (esta última sin usar en
// ningún otro lugar de la app hasta ahora). El stock ya no se edita solo desde Productos: aquí se
// ven los umbrales mínimo/máximo, las alertas, y el historial real de entradas/salidas — que se
// alimenta solo desde el checkout (venta) y desde el CRUD de Productos (ajuste), además de lo que
// se registre manual aquí mismo.
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useToast } from '../../composables/useToast'
import { fechaLocalISO } from '../../composables/useFormat'

const catalog = useCatalogStore()
const { showToast } = useToast()

// Movimientos no se precarga en App.vue (solo se usa en esta vista) - se pide aquí al montar.
onMounted(() => {
  catalog.cargarMovimientos()
})

function estadoStock(producto) {
  const stock = catalog.getProductStock(producto.id_producto)
  const inv = catalog.inventario.find((i) => i.id_producto === producto.id_producto)
  if (stock === 0) return { value: 'agotado', label: 'Agotado', badge: 'badge-red' }
  if (inv && stock <= inv.stock_minimo) return { value: 'stock_bajo', label: 'Stock bajo', badge: 'badge-yellow' }
  return { value: 'disponible', label: 'Disponible', badge: 'badge-green' }
}

// ── Estadísticas ─────────────────────────────────────────────
const totalUnidades = computed(() => catalog.inventario.reduce((s, i) => s + i.cantidad_disponible, 0))
const totalStockBajo = computed(() => catalog.productos.filter((p) => estadoStock(p).value === 'stock_bajo').length)
const totalAgotados = computed(() => catalog.productos.filter((p) => estadoStock(p).value === 'agotado').length)
const movimientosHoy = computed(() => {
  const hoy = fechaLocalISO()
  return catalog.movimientosInventario.filter((m) => m.fecha.startsWith(hoy)).length
})

// ── Alertas ──────────────────────────────────────────────────
function conProducto(lista) {
  return lista.map((i) => ({ ...i, producto: catalog.getProductById(i.id_producto) })).filter((i) => i.producto)
}
// "Crítico" = a punto de agotarse de verdad: muy pocas unidades en términos absolutos, o por
// debajo de la mitad del mínimo configurado — lo que dispare primero. Antes solo miraba el % del
// mínimo, así que un producto con un `stock_minimo` chico (ej. 5) nunca se veía "crítico" aunque
// quedaran solo 3 unidades reales — mismo umbral usado en DashboardView.vue, no inventar otro.
const UMBRAL_CRITICO_ABSOLUTO = 4
function esCriticoInv(item) {
  return item.cantidad_disponible <= UMBRAL_CRITICO_ABSOLUTO || item.cantidad_disponible <= item.stock_minimo * 0.5
}
const alertaAgotados = computed(() => conProducto(catalog.inventario.filter((i) => i.cantidad_disponible === 0)))
const alertaCriticos = computed(() => conProducto(catalog.inventario.filter((i) => i.cantidad_disponible > 0 && esCriticoInv(i))))
const alertaBajos = computed(() =>
  conProducto(catalog.inventario.filter((i) => i.cantidad_disponible > 0 && i.cantidad_disponible <= i.stock_minimo && !esCriticoInv(i)))
)

// ── Gráfico entradas/salidas (últimos 7 días, SVG a mano) ────
const CHART_W = 600
const CHART_H = 190
const CHART_PAD = 24

function efectoStock(m) {
  const signo = { entrada: 1, devolucion: 1, salida: -1, venta: -1 }[m.tipo_movimiento] ?? 1
  return m.tipo_movimiento === 'ajuste' ? m.cantidad : Math.abs(m.cantidad) * signo
}

const ultimos7Dias = computed(() => {
  const dias = []
  for (let i = 6; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    dias.push(fechaLocalISO(d))
  }
  return dias
})

const movimientosPorDia = computed(() =>
  ultimos7Dias.value.map((dia) => {
    const delDia = catalog.movimientosInventario.filter((m) => m.fecha.slice(0, 10) === dia)
    let entradas = 0
    let salidas = 0
    delDia.forEach((m) => {
      const efecto = efectoStock(m)
      if (efecto > 0) entradas += efecto
      else salidas += Math.abs(efecto)
    })
    const label = new Date(dia + 'T00:00:00').toLocaleDateString('es-CO', { weekday: 'short' })
    return { dia, entradas, salidas, label: label.charAt(0).toUpperCase() + label.slice(1) }
  })
)

const maxMovimientoDia = computed(() => Math.max(...movimientosPorDia.value.flatMap((d) => [d.entradas, d.salidas]), 1))
const hayMovimientosRecientes = computed(() => movimientosPorDia.value.some((d) => d.entradas || d.salidas))

const barrasChart = computed(() => {
  const grupoAncho = (CHART_W - CHART_PAD * 2) / movimientosPorDia.value.length
  const barraAncho = grupoAncho * 0.3
  const alturaMax = CHART_H - CHART_PAD * 2
  return movimientosPorDia.value.map((d, i) => {
    const xGrupo = CHART_PAD + i * grupoAncho + grupoAncho * 0.18
    const hEntrada = (d.entradas / maxMovimientoDia.value) * alturaMax
    const hSalida = (d.salidas / maxMovimientoDia.value) * alturaMax
    return {
      ...d,
      xEntrada: xGrupo,
      xSalida: xGrupo + barraAncho + 4,
      yEntrada: CHART_H - CHART_PAD - hEntrada,
      ySalida: CHART_H - CHART_PAD - hSalida,
      hEntrada,
      hSalida,
      ancho: barraAncho,
      xLabel: xGrupo + barraAncho,
    }
  })
})

// ── Tabla de stock por producto ───────────────────────────────
const busqueda = ref('')
const filtroCategoria = ref('')
const filtroEstado = ref('')
const paginaActual = ref(1)
const POR_PAGINA = 8

const productosFiltrados = computed(() => {
  const term = busqueda.value.trim().toLowerCase()
  return catalog.productos
    .filter((p) => {
      const coincideTerm = !term || p.nombre.toLowerCase().includes(term) || p.codigo_producto.toLowerCase().includes(term)
      const coincideCat = !filtroCategoria.value || p.id_categoria === Number(filtroCategoria.value)
      const coincideEstado = !filtroEstado.value || estadoStock(p).value === filtroEstado.value
      return coincideTerm && coincideCat && coincideEstado
    })
    .sort((a, b) => a.id_producto - b.id_producto)
})
const totalPaginas = computed(() => Math.max(1, Math.ceil(productosFiltrados.value.length / POR_PAGINA)))
const productosPagina = computed(() => {
  const inicio = (paginaActual.value - 1) * POR_PAGINA
  return productosFiltrados.value.slice(inicio, inicio + POR_PAGINA)
})
watch([busqueda, filtroCategoria, filtroEstado], () => { paginaActual.value = 1 })

function limpiarFiltros() {
  busqueda.value = ''
  filtroCategoria.value = ''
  filtroEstado.value = ''
}
function irAPagina(n) { paginaActual.value = Math.min(Math.max(1, n), totalPaginas.value) }

// Tarjetas de arriba como filtros reales - mismo patrón ya usado en AdminVentasView.vue (antes
// eran decorativas, sin @click). "Unidades en inventario" no tiene un estado propio que filtrar,
// así que solo limpia y baja a la tabla completa.
const tablaStockRef = ref(null)
function filtrarInventario(estado) {
  filtroEstado.value = estado
  nextTick(() => tablaStockRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

function inventarioDe(id_producto) {
  return catalog.inventario.find((i) => i.id_producto === id_producto)
}

// ── Historial de movimientos ──────────────────────────────────
const TIPO_MOVIMIENTO_INFO = {
  entrada: { label: 'Entrada', badge: 'badge-green', icon: 'ri-arrow-down-line' },
  devolucion: { label: 'Devolución', badge: 'badge-green', icon: 'ri-arrow-go-back-line' },
  salida: { label: 'Salida', badge: 'badge-red', icon: 'ri-arrow-up-line' },
  venta: { label: 'Venta', badge: 'badge-red', icon: 'ri-shopping-cart-2-line' },
  ajuste: { label: 'Ajuste', badge: 'badge-gray', icon: 'ri-equalizer-line' },
}
function nombreUsuario(movimiento) {
  const u = movimiento.usuario
  return u ? `${u.nombre} ${u.apellido}` : 'Sistema'
}
function signoCantidad(m) {
  if (m.tipo_movimiento === 'ajuste') return m.cantidad >= 0 ? '+' : '-'
  return ['entrada', 'devolucion'].includes(m.tipo_movimiento) ? '+' : '-'
}

const busquedaMovimientos = ref('')
const paginaMovimientos = ref(1)
const POR_PAGINA_MOV = 8
const movimientosOrdenados = computed(() =>
  [...catalog.movimientosInventario].sort((a, b) => new Date(b.fecha.replace(' ', 'T')) - new Date(a.fecha.replace(' ', 'T')))
)
const movimientosFiltrados = computed(() => {
  const term = busquedaMovimientos.value.trim().toLowerCase()
  if (!term) return movimientosOrdenados.value
  return movimientosOrdenados.value.filter((m) => {
    const p = catalog.getProductById(m.id_producto)
    return p && (p.nombre.toLowerCase().includes(term) || p.codigo_producto.toLowerCase().includes(term))
  })
})
const totalPaginasMov = computed(() => Math.max(1, Math.ceil(movimientosFiltrados.value.length / POR_PAGINA_MOV)))
const movimientosPagina = computed(() => {
  const inicio = (paginaMovimientos.value - 1) * POR_PAGINA_MOV
  return movimientosFiltrados.value.slice(inicio, inicio + POR_PAGINA_MOV)
})
watch(busquedaMovimientos, () => { paginaMovimientos.value = 1 })
function irAPaginaMov(n) { paginaMovimientos.value = Math.min(Math.max(1, n), totalPaginasMov.value) }

// "Movimientos hoy" solo baja a la tabla (ya viene ordenada del más reciente al más viejo, así
// que los de hoy quedan arriba de una vez) - no hay un filtro de fecha propio en esta tabla como
// sí lo tiene Ventas, no hacía falta construir uno solo para esto.
const tablaMovimientosRef = ref(null)
function verMovimientosHoy() {
  nextTick(() => tablaMovimientosRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

// ── Modal: registrar movimiento manual ────────────────────────
const mostrarModalMovimiento = ref(false)
const formMovimiento = ref({ id_producto: '', tipo_movimiento: 'entrada', cantidad: 1, direccionAjuste: 'aumentar', descripcion: '' })

function abrirRegistrarMovimiento(producto = null) {
  formMovimiento.value = {
    id_producto: producto?.id_producto || '',
    tipo_movimiento: 'entrada',
    cantidad: 1,
    direccionAjuste: 'aumentar',
    descripcion: '',
  }
  mostrarModalMovimiento.value = true
}

async function guardarMovimiento() {
  if (!formMovimiento.value.id_producto) {
    showToast('Selecciona un producto.', 'error')
    return
  }
  if (!formMovimiento.value.cantidad || formMovimiento.value.cantidad <= 0) {
    showToast('Ingresa una cantidad válida.', 'error')
    return
  }
  const esAjusteNegativo = formMovimiento.value.tipo_movimiento === 'ajuste' && formMovimiento.value.direccionAjuste === 'disminuir'
  try {
    await catalog.registrarMovimientoInventario({
      id_producto: Number(formMovimiento.value.id_producto),
      tipo_movimiento: formMovimiento.value.tipo_movimiento,
      cantidad: esAjusteNegativo ? -Math.abs(formMovimiento.value.cantidad) : formMovimiento.value.cantidad,
      descripcion: formMovimiento.value.descripcion || 'Movimiento manual registrado por el administrador',
    })
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo registrar el movimiento.', 'danger')
    return
  }
  showToast('Movimiento registrado correctamente.', 'success')
  mostrarModalMovimiento.value = false
}

// ── Modal: editar umbrales de stock ───────────────────────────
const mostrarModalUmbrales = ref(false)
const formUmbrales = ref({ id_producto: null, nombre: '', stock_minimo: 0, stock_maximo: 0, ubicacion_bodega: '' })

function abrirEditarUmbrales(producto) {
  const inv = inventarioDe(producto.id_producto)
  formUmbrales.value = {
    id_producto: producto.id_producto,
    nombre: producto.nombre,
    stock_minimo: inv?.stock_minimo ?? 0,
    stock_maximo: inv?.stock_maximo ?? 0,
    ubicacion_bodega: inv?.ubicacion_bodega ?? '',
  }
  mostrarModalUmbrales.value = true
}

async function guardarUmbrales() {
  try {
    await catalog.actualizarUmbralesStock(formUmbrales.value.id_producto, {
      stock_minimo: formUmbrales.value.stock_minimo,
      stock_maximo: formUmbrales.value.stock_maximo,
      ubicacion_bodega: formUmbrales.value.ubicacion_bodega,
    })
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudieron actualizar los umbrales.', 'danger')
    return
  }
  showToast('Umbrales de stock actualizados.', 'success')
  mostrarModalUmbrales.value = false
}
</script>

<template>
  <div class="admin-page-header">
    <div>
      <h1>Inventario</h1>
      <p>Control de existencias, umbrales de stock y movimientos de entrada y salida del almacén.</p>
    </div>
    <button type="button" class="btn btn-primary" @click="abrirRegistrarMovimiento()">
      <i class="ri-exchange-2-line"></i> Registrar movimiento
    </button>
  </div>

  <!-- ESTADÍSTICAS -->
  <div class="admin-stats-grid">
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="filtrarInventario('')">
      <div class="admin-stat-top">
        <span class="admin-stat-icon icon-azul"><i class="ri-archive-2-line"></i></span>
      </div>
      <strong class="admin-stat-value">{{ totalUnidades }}</strong>
      <span class="admin-stat-label">Unidades en inventario</span>
    </button>
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="filtrarInventario('stock_bajo')">
      <div class="admin-stat-top">
        <span class="admin-stat-icon icon-alerta"><i class="ri-error-warning-line"></i></span>
      </div>
      <strong class="admin-stat-value">{{ totalStockBajo }}</strong>
      <span class="admin-stat-label">Stock bajo</span>
    </button>
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="filtrarInventario('agotado')">
      <div class="admin-stat-top">
        <span class="admin-stat-icon icon-rojo"><i class="ri-close-circle-line"></i></span>
        <span v-if="totalAgotados" class="admin-stat-sub" style="background:rgba(192,57,43,0.12);color:var(--primary);">Crítico</span>
      </div>
      <strong class="admin-stat-value">{{ totalAgotados }}</strong>
      <span class="admin-stat-label">Agotados</span>
    </button>
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="verMovimientosHoy">
      <div class="admin-stat-top">
        <span class="admin-stat-icon icon-verde"><i class="ri-exchange-2-line"></i></span>
      </div>
      <strong class="admin-stat-value">{{ movimientosHoy }}</strong>
      <span class="admin-stat-label">Movimientos hoy</span>
    </button>
  </div>

  <!-- GRÁFICO + ALERTAS -->
  <div class="admin-bottom-grid inv-top-grid">
    <div class="admin-card">
      <div class="admin-card-header">
        <h2>Movimientos de inventario</h2>
        <span class="inv-chart-legend">
          <i class="inv-legend-dot dot-verde"></i> Entradas
          <i class="inv-legend-dot dot-roja"></i> Salidas
        </span>
      </div>
      <div style="padding: 16px 20px 20px;">
        <div v-if="!hayMovimientosRecientes" class="admin-empty" style="padding: 30px 0;">
          <i class="ri-bar-chart-line"></i>
          <p>Todavía no hay movimientos registrados esta semana.</p>
        </div>
        <template v-else>
          <svg :viewBox="`0 0 ${CHART_W} ${CHART_H}`" class="inv-chart">
            <line v-for="i in 3" :key="i" x1="24" :y1="24 + i * 38" :x2="CHART_W - 24" :y2="24 + i * 38" stroke="#E0E0E0" stroke-dasharray="4 4" />
            <g v-for="b in barrasChart" :key="b.dia">
              <rect :x="b.xEntrada" :y="b.yEntrada" :width="b.ancho" :height="b.hEntrada" rx="3" fill="var(--success)">
                <title>{{ b.label }}: {{ b.entradas }} entradas</title>
              </rect>
              <text v-if="b.entradas" :x="b.xEntrada + b.ancho / 2" :y="b.yEntrada - 6" text-anchor="middle" class="inv-chart-value">{{ b.entradas }}</text>
              <rect :x="b.xSalida" :y="b.ySalida" :width="b.ancho" :height="b.hSalida" rx="3" fill="var(--primary)">
                <title>{{ b.label }}: {{ b.salidas }} salidas</title>
              </rect>
              <text v-if="b.salidas" :x="b.xSalida + b.ancho / 2" :y="b.ySalida - 6" text-anchor="middle" class="inv-chart-value">{{ b.salidas }}</text>
            </g>
          </svg>
          <div class="inv-chart-labels">
            <span v-for="b in barrasChart" :key="b.dia">{{ b.label }}</span>
          </div>
        </template>
      </div>
    </div>

    <div class="admin-card">
      <div class="admin-card-header"><h2>Alertas de inventario</h2></div>
      <div class="inv-alertas-list">
        <div v-if="!alertaAgotados.length && !alertaCriticos.length && !alertaBajos.length" class="admin-empty" style="padding: 30px 0;">
          <i class="ri-checkbox-circle-line"></i>
          <p>Todo el inventario está en buen nivel.</p>
        </div>
        <template v-else>
          <div v-for="item in alertaAgotados" :key="'ago-' + item.id_producto" class="inv-alerta-item">
            <span class="badge badge-red">Agotado</span>
            <strong :title="item.producto.nombre">{{ item.producto.nombre }}</strong>
            <button type="button" class="admin-action-btn" title="Registrar entrada" @click="abrirRegistrarMovimiento(item.producto)"><i class="ri-add-line"></i></button>
          </div>
          <div v-for="item in alertaCriticos" :key="'crit-' + item.id_producto" class="inv-alerta-item">
            <span class="badge badge-red">Crítico</span>
            <strong :title="item.producto.nombre">{{ item.producto.nombre }}</strong>
            <span class="inv-alerta-cantidad">{{ item.cantidad_disponible }} und</span>
          </div>
          <div v-for="item in alertaBajos" :key="'bajo-' + item.id_producto" class="inv-alerta-item">
            <span class="badge badge-yellow">Reposición</span>
            <strong :title="item.producto.nombre">{{ item.producto.nombre }}</strong>
            <span class="inv-alerta-cantidad">{{ item.cantidad_disponible }} und</span>
          </div>
        </template>
      </div>
    </div>
  </div>

  <!-- FILTROS -->
  <div class="admin-filters-bar">
    <div class="search-input-wrap">
      <input v-model="busqueda" type="search" class="form-control" placeholder="Buscar por nombre o código..." />
    </div>
    <select v-model="filtroCategoria">
      <option value="">Categoría</option>
      <option v-for="c in catalog.categorias" :key="c.id_categoria" :value="c.id_categoria">{{ c.nombre }}</option>
    </select>
    <select v-model="filtroEstado">
      <option value="">Estado</option>
      <option value="disponible">Disponible</option>
      <option value="stock_bajo">Stock Bajo</option>
      <option value="agotado">Agotado</option>
    </select>
    <button type="button" class="btn btn-outline-red btn-sm" @click="limpiarFiltros">
      <i class="ri-filter-off-line"></i> Limpiar filtros
    </button>
  </div>

  <!-- TABLA DE STOCK -->
  <div class="admin-card" ref="tablaStockRef">
    <div class="admin-card-header">
      <h2>Stock por producto</h2>
      <span>{{ productosFiltrados.length }} registros encontrados</span>
    </div>

    <div v-if="!productosPagina.length" class="admin-empty">
      <i class="ri-inbox-line"></i>
      <p>No hay productos que coincidan con estos filtros.</p>
    </div>

    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>Producto</th>
            <th>Categoría</th>
            <th>Disponible</th>
            <th>Reservado</th>
            <th>Mín. / Máx.</th>
            <th>Ubicación</th>
            <th>Estado</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in productosPagina" :key="p.id_producto">
            <td>
              <div class="admin-prod-cell">
                <img :src="p.imagen_url" :alt="p.nombre" class="admin-prod-thumb" />
                <div class="admin-prod-info">
                  <strong :title="p.nombre">{{ p.nombre }}</strong>
                  <span>Cód: {{ p.codigo_producto }}</span>
                </div>
              </div>
            </td>
            <td>{{ catalog.getCategoryName(p.id_categoria) }}</td>
            <td>{{ catalog.getProductStock(p.id_producto) }} und</td>
            <td>{{ inventarioDe(p.id_producto)?.cantidad_reservada || 0 }} und</td>
            <td>{{ inventarioDe(p.id_producto)?.stock_minimo ?? '—' }} / {{ inventarioDe(p.id_producto)?.stock_maximo ?? '—' }}</td>
            <td>{{ inventarioDe(p.id_producto)?.ubicacion_bodega || '—' }}</td>
            <td><span class="badge" :class="estadoStock(p).badge">{{ estadoStock(p).label }}</span></td>
            <td>
              <div class="admin-actions-cell">
                <button type="button" class="admin-action-btn" title="Editar umbrales" @click="abrirEditarUmbrales(p)"><i class="ri-settings-3-line"></i></button>
                <button type="button" class="admin-action-btn" title="Registrar movimiento" @click="abrirRegistrarMovimiento(p)"><i class="ri-exchange-2-line"></i></button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="totalPaginas > 1" class="admin-pagination">
      <button type="button" class="admin-page-btn" :disabled="paginaActual === 1" @click="irAPagina(paginaActual - 1)"><i class="ri-arrow-left-s-line"></i></button>
      <button v-for="n in totalPaginas" :key="n" type="button" class="admin-page-btn" :class="{ active: n === paginaActual }" @click="irAPagina(n)">{{ n }}</button>
      <button type="button" class="admin-page-btn" :disabled="paginaActual === totalPaginas" @click="irAPagina(paginaActual + 1)"><i class="ri-arrow-right-s-line"></i></button>
    </div>
  </div>

  <!-- HISTORIAL DE MOVIMIENTOS -->
  <div class="admin-card" ref="tablaMovimientosRef">
    <div class="admin-card-header">
      <h2>Últimos movimientos</h2>
      <input v-model="busquedaMovimientos" type="search" class="form-control inv-mov-search" placeholder="Buscar por producto o código..." />
      <span>{{ movimientosFiltrados.length }} registros</span>
    </div>

    <div v-if="!movimientosPagina.length" class="admin-empty">
      <i class="ri-exchange-2-line"></i>
      <p>Todavía no se han registrado movimientos de inventario.</p>
    </div>

    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>Fecha</th>
            <th>Producto</th>
            <th>Tipo</th>
            <th>Cantidad</th>
            <th>Responsable</th>
            <th>Descripción</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in movimientosPagina" :key="m.id_movimiento">
            <td>{{ m.fecha }}</td>
            <td>
              <div class="admin-prod-info">
                <strong :title="catalog.getProductById(m.id_producto)?.nombre">{{ catalog.getProductById(m.id_producto)?.nombre || 'Producto eliminado' }}</strong>
                <span v-if="catalog.getProductById(m.id_producto)">Cód: {{ catalog.getProductById(m.id_producto).codigo_producto }}</span>
              </div>
            </td>
            <td>
              <span class="badge" :class="TIPO_MOVIMIENTO_INFO[m.tipo_movimiento].badge">
                <i :class="TIPO_MOVIMIENTO_INFO[m.tipo_movimiento].icon"></i> {{ TIPO_MOVIMIENTO_INFO[m.tipo_movimiento].label }}
              </span>
            </td>
            <td>{{ signoCantidad(m) }}{{ Math.abs(m.cantidad) }} und</td>
            <td>{{ nombreUsuario(m) }}</td>
            <td class="inv-descripcion-cell" :title="m.descripcion">{{ m.descripcion || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="totalPaginasMov > 1" class="admin-pagination">
      <button type="button" class="admin-page-btn" :disabled="paginaMovimientos === 1" @click="irAPaginaMov(paginaMovimientos - 1)"><i class="ri-arrow-left-s-line"></i></button>
      <button v-for="n in totalPaginasMov" :key="n" type="button" class="admin-page-btn" :class="{ active: n === paginaMovimientos }" @click="irAPaginaMov(n)">{{ n }}</button>
      <button type="button" class="admin-page-btn" :disabled="paginaMovimientos === totalPaginasMov" @click="irAPaginaMov(paginaMovimientos + 1)"><i class="ri-arrow-right-s-line"></i></button>
    </div>
  </div>

  <!-- MODAL: REGISTRAR MOVIMIENTO -->
  <Transition name="confirm-modal-fade">
    <div v-if="mostrarModalMovimiento" class="confirm-modal-overlay" @click.self="mostrarModalMovimiento = false">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal producto-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="mostrarModalMovimiento = false"><i class="ri-close-line"></i></button>
          <h3 class="confirm-modal-title">Registrar movimiento</h3>

          <form class="form-grid-2" @submit.prevent="guardarMovimiento">
            <div class="form-group full">
              <label class="form-label required">Producto</label>
              <select v-model="formMovimiento.id_producto" class="form-control" required>
                <option value="" disabled>Selecciona un producto</option>
                <option v-for="p in catalog.productos" :key="p.id_producto" :value="p.id_producto">{{ p.nombre }} (Cód: {{ p.codigo_producto }})</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label required">Tipo de movimiento</label>
              <select v-model="formMovimiento.tipo_movimiento" class="form-control" required>
                <option value="entrada">Entrada</option>
                <option value="salida">Salida</option>
                <option value="ajuste">Ajuste por conteo</option>
                <option value="devolucion">Devolución de cliente</option>
              </select>
            </div>
            <div class="form-group" v-if="formMovimiento.tipo_movimiento === 'ajuste'">
              <label class="form-label required">Dirección del ajuste</label>
              <select v-model="formMovimiento.direccionAjuste" class="form-control" required>
                <option value="aumentar">Aumenta el stock</option>
                <option value="disminuir">Disminuye el stock</option>
              </select>
            </div>

            <div class="form-group" :class="{ full: formMovimiento.tipo_movimiento !== 'ajuste' }">
              <label class="form-label required">Cantidad</label>
              <input v-model.number="formMovimiento.cantidad" type="number" min="1" class="form-control" required />
            </div>

            <div class="form-group full">
              <label class="form-label">Descripción</label>
              <textarea v-model="formMovimiento.descripcion" class="form-control" rows="2" placeholder="Ej. Reposición de proveedor, corrección de conteo..."></textarea>
              <span class="form-hint">Opcional — si la dejas vacía, queda registrada como "Movimiento manual registrado por el administrador".</span>
            </div>

            <div class="form-actions full">
              <button type="button" class="btn btn-outline-red btn-sm" @click="mostrarModalMovimiento = false">Cancelar</button>
              <button type="submit" class="btn btn-primary btn-sm"><i class="ri-save-line"></i> Registrar</button>
            </div>
          </form>
        </div>
      </Transition>
    </div>
  </Transition>

  <!-- MODAL: EDITAR UMBRALES -->
  <Transition name="confirm-modal-fade">
    <div v-if="mostrarModalUmbrales" class="confirm-modal-overlay" @click.self="mostrarModalUmbrales = false">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal producto-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="mostrarModalUmbrales = false"><i class="ri-close-line"></i></button>
          <h3 class="confirm-modal-title">Umbrales de stock</h3>
          <p class="mb-16" style="text-align:center;color:var(--text-muted);font-size:0.85rem;">{{ formUmbrales.nombre }}</p>

          <form class="form-grid-2" @submit.prevent="guardarUmbrales">
            <div class="form-group">
              <label class="form-label required">Stock mínimo</label>
              <input v-model.number="formUmbrales.stock_minimo" type="number" min="0" class="form-control" required />
            </div>
            <div class="form-group">
              <label class="form-label required">Stock máximo</label>
              <input v-model.number="formUmbrales.stock_maximo" type="number" min="0" class="form-control" required />
            </div>
            <div class="form-group full">
              <label class="form-label">Ubicación en bodega</label>
              <input v-model="formUmbrales.ubicacion_bodega" type="text" class="form-control" placeholder="Ej. Bodega A - Estante 3" />
              <span class="form-hint">Opcional — solo si manejas varias bodegas o estantes.</span>
            </div>

            <div class="form-actions full">
              <button type="button" class="btn btn-outline-red btn-sm" @click="mostrarModalUmbrales = false">Cancelar</button>
              <button type="submit" class="btn btn-primary btn-sm"><i class="ri-save-line"></i> Guardar cambios</button>
            </div>
          </form>
        </div>
      </Transition>
    </div>
  </Transition>
</template>

<style scoped>
/* Tarjetas de arriba como botones reales (antes eran decorativas) - mismo patrón/clase que ya
   usa AdminVentasView.vue, cada vista mantiene su propia copia en vez de una global. */
.admin-stat-card-btn { display: block; width: 100%; text-align: left; cursor: pointer; transition: var(--transition); }
.admin-stat-card-btn:hover { transform: translateY(-2px); box-shadow: var(--shadow); border-color: transparent; }

.icon-azul { background: rgba(41,128,185,0.1); color: var(--info); }
.icon-verde { background: rgba(39,174,96,0.1); color: var(--success); }
.icon-alerta { background: rgba(243,156,18,0.12); color: var(--warning); }
.icon-rojo { background: rgba(192,57,43,0.1); color: var(--primary); }

.inv-top-grid { grid-template-columns: 1.5fr 1fr; }

.inv-chart-legend { display: flex; align-items: center; gap: 14px; font-size: 0.78rem; color: var(--text-muted); font-weight: 600; }
.inv-legend-dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; margin-right: 4px; }
.dot-verde { background: var(--success); }
.dot-roja { background: var(--primary); }
.inv-chart { width: 100%; height: auto; display: block; }
.inv-chart-labels { display: flex; justify-content: space-between; margin-top: 8px; padding: 0 10px; }
.inv-chart-labels span { font-size: 0.74rem; color: var(--text-muted); font-weight: 600; text-align: center; flex: 1; }
.inv-chart-value { font-size: 10px; font-weight: 700; fill: var(--secondary); font-family: var(--font-main); }

.inv-mov-search { max-width: 260px; }

.inv-alertas-list { padding: 8px 20px 16px; max-height: 320px; overflow-y: auto; }
.inv-alerta-item { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--border); }
.inv-alerta-item:last-child { border-bottom: none; }
.inv-alerta-item strong { flex: 1; font-size: 0.85rem; color: var(--secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.inv-alerta-cantidad { font-size: 0.78rem; color: var(--text-muted); font-weight: 600; flex-shrink: 0; }

.inv-descripcion-cell { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 1100px) {
  .inv-top-grid { grid-template-columns: 1fr; }
}
</style>
