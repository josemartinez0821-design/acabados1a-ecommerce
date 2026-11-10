<script setup>
// Sin RF oficial (ampliación del alcance, ver docs/MATRIZ_TRAZABILIDAD.md) - detalle de servicio: qué incluye, cómo funciona, galería con lightbox, cotización (requiere sesión)
import { ref, computed } from 'vue'
import { useCatalogStore } from '../stores/catalog'
import { unidadServicio, tarifaServicio } from '../stores/cotizaciones'
import { formatCOP } from '../composables/useFormat'
import { useCotizarGate } from '../composables/useCotizarGate'
import CotizarLoginModal from '../components/service/CotizarLoginModal.vue'

const props = defineProps({ id: { type: String, required: true } })
const catalog = useCatalogStore()
const { mostrarModalLogin, irACotizar, irALogin } = useCotizarGate()

const servicio = computed(() => catalog.getServiceById(props.id))

// Etiqueta legible del ENUM `tipo_servicio` (con tildes y siglas). Mismas parejas que el
// filtro de TIPOS en ServiciosView / AdminServiciosView — si algún día se unifican, extraer.
const TIPO_LABEL = {
  drywall: 'Drywall', aplicacion_pintura: 'Aplicación de Pintura', asesoria: 'Asesoría',
  pvc: 'PVC', mantenimiento: 'Mantenimiento', diseño_interiores: 'Diseño de Interiores',
  diseño_exteriores: 'Diseño de Exteriores', instalacion: 'Instalación', consultoria: 'Consultoría',
}
const tipoLegible = (tipo) => TIPO_LABEL[tipo] || (tipo || '').replaceAll('_', ' ')

const horas = ref(1)
const totalEstimado = computed(() => {
  if (!servicio.value) return 0
  if (unidadServicio(servicio.value) === 'proyecto') return servicio.value.precio_proyecto
  return tarifaServicio(servicio.value) * horas.value
})

// Solo los servicios donde el negocio pone los materiales (incluye_materiales en el backend:
// drywall, PVC, pisos, estuco, cielo raso) tienen las dos modalidades. En asesorías, consultorías
// y mantenimientos el negocio nunca pone materiales, así que el toggle no aplica y no se muestra.
const tieneModalidad = computed(() => !!servicio.value?.incluye_materiales)

// El incluye_materiales del servicio es solo la preselección: si tiene modalidad, el cliente
// puede cambiarla. Si no la tiene, `modo` queda fijo en 'solo_servicio' (solo mano de obra).
const modoElegido = ref(null)
const modo = computed(() => {
  if (!tieneModalidad.value) return 'solo_servicio'
  return modoElegido.value || 'todo_incluido'
})

const HINT_MODO = {
  todo_incluido: 'Nosotros ponemos los materiales y la mano de obra.',
  solo_servicio: 'Tú pones los materiales; nosotros, la mano de obra.',
}

// "¿Qué incluye?" concuerda con la modalidad elegida: en "Solo servicio" se ocultan las líneas
// marcadas como material (las pone el cliente) y se aclara con una línea aparte.
const materialesLosPoneCliente = computed(() => tieneModalidad.value && modo.value === 'solo_servicio')
const queIncluye = computed(() => {
  const items = servicio.value?.que_incluye || []
  return materialesLosPoneCliente.value ? items.filter((i) => !i.material) : items
})

const seCobraPorTiempo = computed(() => !!(servicio.value?.precio_hora || servicio.value?.precio_dia))

// Un servicio eliminado (borrado lógico) no sale en el listado, pero su enlace directo sigue
// abriendo esta página: no se deja cotizar (el backend también lo rechaza en CotizacionService).
const servicioInactivo = computed(() => servicio.value?.activo === false)

function solicitarCotizacion() {
  if (servicioInactivo.value) return
  // Solo se arrastran a la cotización los datos que aplican a este servicio: las horas/días si
  // se cobra por tiempo, y el modo si tiene las dos modalidades (materiales incluidos o no).
  // En un servicio a precio fijo de proyecto, ninguno de los dos aplica.
  irACotizar({
    tipo: 'servicio',
    id: servicio.value.id_servicio,
    horas: seCobraPorTiempo.value ? horas.value : undefined,
    modo: tieneModalidad.value ? modo.value : undefined,
  })
}

const imagenLightbox = ref(null)

const relacionados = computed(() => {
  if (!servicio.value) return []
  return catalog.serviciosCatalogo.filter((s) => s.id_servicio !== servicio.value.id_servicio).slice(0, 4)
})

function compartirWhatsapp() {
  const texto = encodeURIComponent(`Mira este servicio de Acabados y Diseños 1A: ${servicio.value.nombre_servicio} — ${window.location.href}`)
  window.open(`https://wa.me/?text=${texto}`, '_blank', 'noopener')
}

function copiarEnlace() {
  navigator.clipboard.writeText(window.location.href)
}
</script>

<template>
  <template v-if="servicio">
    <div class="container">
      <div class="breadcrumb">
        <span class="breadcrumb-item"><RouterLink to="/"><i class="ri-home-4-line"></i> Inicio</RouterLink></span>
        <span class="breadcrumb-sep"><i class="ri-arrow-right-s-line"></i></span>
        <span class="breadcrumb-item"><RouterLink to="/servicios"><i class="ri-tools-line"></i> Servicios</RouterLink></span>
        <span class="breadcrumb-sep"><i class="ri-arrow-right-s-line"></i></span>
        <span class="breadcrumb-item active">{{ servicio.nombre_servicio }}</span>
      </div>
    </div>

    <section class="section" style="padding-top:0;">
      <div class="container">
        <div class="servicio-hero">
          <img :src="servicio.imagen_detalle_url || servicio.imagen_url" :alt="servicio.nombre_servicio" />
          <div class="servicio-hero-overlay">
            <span class="badge badge-red" style="width:fit-content;">{{ tipoLegible(servicio.tipo_servicio) }}</span>
            <h1 class="servicio-hero-title">{{ servicio.nombre_servicio }}</h1>
            <p class="servicio-hero-rating">
              <i class="ri-star-fill" style="color:var(--accent);"></i> {{ servicio.rating }}
              &middot; {{ servicio.codigo_servicio }}
            </p>
          </div>
        </div>

        <div class="servicio-layout">
          <div>
            <div class="admin-card" style="margin-bottom:20px;">
              <div class="admin-card-body">
                <h2 class="font-main fw-800 mb-16" style="font-size:1.1rem;"><i class="ri-information-line" style="color:var(--primary);"></i> Sobre este servicio</h2>
                <p style="color:var(--text-light);line-height:1.8;">{{ servicio.descripcion }}</p>

                <div class="servicio-quickfacts">
                  <div class="servicio-quickfact">
                    <div class="servicio-quickfact-icon"><i class="ri-time-line"></i></div>
                    <div class="servicio-quickfact-text"><span>Respuesta</span><strong>Máx. 24 horas</strong></div>
                  </div>
                  <div class="servicio-quickfact">
                    <div class="servicio-quickfact-icon"><i class="ri-tools-line"></i></div>
                    <div class="servicio-quickfact-text">
                      <span>Materiales</span>
                      <strong v-if="tieneModalidad">{{ modo === 'todo_incluido' ? 'Los ponemos nosotros' : 'Los pones tú' }}</strong>
                      <strong v-else>No incluidos</strong>
                    </div>
                  </div>
                  <div class="servicio-quickfact">
                    <div class="servicio-quickfact-icon"><i class="ri-shield-check-line"></i></div>
                    <div class="servicio-quickfact-text"><span>Garantía</span><strong>{{ servicio.garantia_meses }} {{ servicio.garantia_meses === 1 ? 'mes' : 'meses' }}</strong></div>
                  </div>
                  <div class="servicio-quickfact">
                    <div class="servicio-quickfact-icon"><i class="ri-map-pin-line"></i></div>
                    <div class="servicio-quickfact-text"><span>Cobertura</span><strong>{{ servicio.zona_cobertura }}</strong></div>
                  </div>
                </div>
              </div>
            </div>

            <div class="admin-card" style="margin-bottom:20px;">
              <div class="admin-card-body">
                <h2 class="font-main fw-800 mb-16" style="font-size:1.1rem;"><i class="ri-checkbox-multiple-line" style="color:var(--primary);"></i> ¿Qué incluye este servicio?</h2>
                <div class="servicio-incluye-grid">
                  <div v-for="item in queIncluye" :key="item.t" class="servicio-incluye-item">
                    <i class="ri-checkbox-circle-fill"></i> {{ item.t }}
                  </div>
                  <div v-if="materialesLosPoneCliente" class="servicio-incluye-item servicio-incluye-item-off">
                    <i class="ri-close-circle-line"></i> Los materiales los aportas tú
                  </div>
                </div>
              </div>
            </div>

            <div class="admin-card" style="margin-bottom:20px;">
              <div class="admin-card-body">
                <h2 class="font-main fw-800 mb-16" style="font-size:1.1rem;"><i class="ri-route-line" style="color:var(--primary);"></i> ¿Cómo funciona?</h2>
                <div class="servicio-pasos">
                  <div v-for="(paso, i) in servicio.como_funciona" :key="paso.titulo" class="servicio-paso">
                    <div class="servicio-paso-num">{{ i + 1 }}</div>
                    <div>
                      <div class="servicio-paso-titulo">{{ paso.titulo }}</div>
                      <div class="servicio-paso-desc">{{ paso.descripcion }}</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="servicio.galeria?.length" class="admin-card">
              <div class="admin-card-body">
                <h2 class="font-main fw-800 mb-16" style="font-size:1.1rem;"><i class="ri-gallery-line" style="color:var(--primary);"></i> Galería de trabajos realizados</h2>
                <div class="servicio-galeria">
                  <div
                    v-for="(img, i) in servicio.galeria"
                    :key="i"
                    class="servicio-galeria-item"
                    @click="imagenLightbox = img"
                  >
                    <img :src="img" :alt="`${servicio.nombre_servicio} ${i + 1}`" />
                  </div>
                </div>
              </div>
            </div>
          </div>

          <aside class="order-summary sd-price-card">
            <p class="text-muted" style="font-size:0.78rem;">PRECIO</p>
            <div class="sd-price-top">
              <span class="sd-price-value">
                <template v-if="servicio.precio_hora">{{ formatCOP(servicio.precio_hora) }}</template>
                <template v-else-if="servicio.precio_dia">{{ formatCOP(servicio.precio_dia) }}</template>
                <template v-else>Desde {{ formatCOP(servicio.precio_proyecto) }}</template>
              </span>
              <span v-if="servicio.precio_hora" class="sd-price-unit">/hora</span>
              <span v-else-if="servicio.precio_dia" class="sd-price-unit">/día</span>
            </div>

            <template v-if="seCobraPorTiempo">
              <div class="sd-horas-card">
                <div class="sd-horas-card-icon"><i class="ri-time-line"></i></div>
                <div class="sd-horas-card-body">
                  <label>{{ servicio.precio_dia ? 'Pon los días estimados de tu próximo proyecto' : 'Pon las horas estimadas de tu próximo proyecto' }}</label>
                  <div class="sd-stepper-row">
                    <span class="sd-stepper-label">{{ servicio.precio_dia ? 'Días estimados' : 'Horas estimadas' }}</span>
                    <div class="sd-stepper">
                      <button type="button" @click="horas = Math.max(1, horas - 1)"><i class="ri-subtract-line"></i></button>
                      <input type="number" :value="horas" readonly />
                      <button type="button" @click="horas++"><i class="ri-add-line"></i></button>
                    </div>
                  </div>
                </div>
              </div>
              <div class="d-flex justify-between align-center mb-16">
                <span class="text-muted" style="font-size:0.85rem;">Total estimado</span>
                <strong class="font-main" style="font-size:1.2rem;color:var(--secondary);">{{ formatCOP(totalEstimado) }}</strong>
              </div>
            </template>

            <template v-if="tieneModalidad">
              <div class="cotiz-modo-toggle">
                <button type="button" class="cotiz-modo-chip" :class="{ active: modo === 'todo_incluido' }" @click="modoElegido = 'todo_incluido'">
                  <i class="ri-checkbox-multiple-line"></i> Todo incluido
                </button>
                <button type="button" class="cotiz-modo-chip" :class="{ active: modo === 'solo_servicio' }" @click="modoElegido = 'solo_servicio'">
                  <i class="ri-hammer-line"></i> Solo servicio
                </button>
              </div>
              <p class="cotiz-modo-warning" style="margin-bottom:16px;"><i class="ri-information-line"></i> {{ HINT_MODO[modo] }}</p>
            </template>

            <p class="text-muted" style="font-size:0.78rem;margin-bottom:20px;"><i class="ri-time-line"></i> {{ servicio.horario_atencion }}</p>

            <div v-if="servicioInactivo" class="alert alert-danger" style="margin-bottom:10px;">
              <i class="ri-error-warning-line"></i> Este servicio ya no está disponible. Explora nuestros otros servicios.
            </div>
            <button class="btn btn-primary btn-lg btn-block" :disabled="servicioInactivo" @click="solicitarCotizacion">
              <i class="ri-file-list-line"></i> Solicitar Cotización
            </button>
            <a :href="`https://wa.me/${catalog.configuracion.whatsapp}`" target="_blank" rel="noopener" class="btn btn-outline-red btn-lg btn-block" style="margin-top:10px;">
              <i class="ri-whatsapp-line"></i> Preguntar por WhatsApp
            </a>

            <div class="sd-share">
              <span>Compartir:</span>
              <button class="sd-share-btn" @click="compartirWhatsapp" aria-label="Compartir por WhatsApp"><i class="ri-whatsapp-line"></i></button>
              <button class="sd-share-btn" @click="copiarEnlace" aria-label="Copiar enlace"><i class="ri-link"></i></button>
            </div>
          </aside>
        </div>

        <div v-if="relacionados.length" style="margin-top:60px;">
          <h2 class="section-title" style="text-align:left;">Otros servicios de interés</h2>
          <div class="services-grid">
            <div class="service-card" v-for="s in relacionados" :key="s.id_servicio">
              <RouterLink :to="`/servicios/${s.id_servicio}`" class="service-card-img">
                <img :src="s.imagen_url" :alt="s.nombre_servicio" loading="lazy" />
              </RouterLink>
              <div class="service-card-body">
                <div class="service-type">{{ tipoLegible(s.tipo_servicio) }}</div>
                <h3 class="service-name">{{ s.nombre_servicio }}</h3>
                <p class="service-desc">{{ s.descripcion }}</p>
                <RouterLink :to="`/servicios/${s.id_servicio}`" class="btn btn-outline-red btn-sm btn-block">Ver Servicio</RouterLink>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <Transition name="confirm-modal-fade">
      <div v-if="imagenLightbox" class="lightbox-overlay" @click.self="imagenLightbox = null">
        <button class="lightbox-close" aria-label="Cerrar" @click="imagenLightbox = null"><i class="ri-close-line"></i></button>
        <img :src="imagenLightbox" alt="Trabajo realizado" class="lightbox-img" />
      </div>
    </Transition>

    <CotizarLoginModal :mostrar="mostrarModalLogin" @cerrar="mostrarModalLogin = false" @iniciar-sesion="irALogin" />
  </template>

  <div v-else class="container" style="padding:80px 0;text-align:center;">
    <p>No se encontró el servicio.</p>
    <RouterLink to="/servicios" class="btn btn-primary" style="margin-top:16px;">Volver a servicios</RouterLink>
  </div>
</template>

<style scoped>
.servicio-hero { position: relative; border-radius: var(--radius-lg); overflow: hidden; height: 320px; margin: 40px 0; }
.servicio-hero img { width: 100%; height: 100%; object-fit: cover; }
.servicio-hero-overlay {
  position: absolute; inset: 0; background: linear-gradient(to top, rgba(26,26,46,0.9), rgba(26,26,46,0.1));
  display: flex; flex-direction: column; justify-content: flex-end; gap: 8px; padding: 32px;
}
.servicio-hero-title { font-family: var(--font-main); font-weight: 800; font-size: 1.8rem; color: white; }
.servicio-hero-rating { color: rgba(255,255,255,0.75); font-size: 0.88rem; }

.servicio-layout { display: grid; grid-template-columns: 2fr 1fr; gap: 24px; align-items: start; }

.servicio-incluye-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
.servicio-incluye-item { display: flex; align-items: flex-start; gap: 8px; font-size: 0.9rem; color: var(--text); }
.servicio-incluye-item i { color: var(--success); margin-top: 2px; }
.servicio-incluye-item-off { color: var(--text-muted); }
.servicio-incluye-item-off i { color: var(--text-muted); }

.servicio-pasos { position: relative; }
.servicio-paso { display: flex; gap: 16px; padding-bottom: 24px; position: relative; }
.servicio-paso:last-child { padding-bottom: 0; }
.servicio-paso:not(:last-child)::before {
  content: ''; position: absolute; left: 15px; top: 34px; bottom: 0; width: 2px; background: var(--border);
}
.servicio-paso-num {
  width: 32px; height: 32px; border-radius: 50%; background: var(--primary); color: white;
  display: flex; align-items: center; justify-content: center; font-family: var(--font-main); font-weight: 700;
  font-size: 0.85rem; flex-shrink: 0; z-index: 1;
}
.servicio-paso-titulo { font-family: var(--font-main); font-weight: 700; color: var(--secondary); font-size: 0.95rem; margin-bottom: 2px; }
.servicio-paso-desc { color: var(--text-light); font-size: 0.85rem; }

.servicio-galeria { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; }

@media (max-width: 900px) {
  .servicio-layout { grid-template-columns: 1fr; }
  .servicio-incluye-grid { grid-template-columns: 1fr; }
}
</style>
