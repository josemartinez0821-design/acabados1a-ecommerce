<script setup>
// Gestión de cotizaciones para el admin -> tablas `cotizaciones`, `cotizacion_productos`, `cotizacion_servicios`.
// Antes esta vista llamaba a un endpoint que no existe (api.get('/admin/cotizaciones')) y por eso
// nunca mostraba nada: el cliente y el admin leían de fuentes distintas. Ahora usa el mismo store
// reactivo que ya usa `CotizacionesView.vue`, así que una solicitud nueva aparece aquí al instante.
import { ref, computed } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useCotizacionesStore, unidadServicio } from '../../stores/cotizaciones'
import { useVentasStore } from '../../stores/ventas'
import { useToast } from '../../composables/useToast'
import { formatCOP, formatDate, formatDateTime, extraerFechaDeseada, extraerNotaCliente, fechaLocalISO } from '../../composables/useFormat'

const catalog = useCatalogStore()
const cotizStore = useCotizacionesStore()
const ventasStore = useVentasStore()
const { showToast } = useToast()

// El anticipo (50%) de una cotización de servicio se guarda como una `venta` real ligada por
// `id_cotizacion` (sin productos, ver crearVenta() en CotizacionesView.vue) — esto la busca para
// mostrarla directo en el detalle de la cotización, en vez de que el admin tenga que ir a Ventas.
function pagoDeCotizacion(id_cotizacion) {
  const venta = ventasStore.ventas.find((v) => v.id_cotizacion === id_cotizacion)
  if (!venta) return null
  return { venta, pago: ventasStore.getPagosDeVenta(venta.id_venta)[0] }
}

// Cuánto falta para que venza la aprobación — misma función que en CotizacionesView.vue (cliente),
// el admin necesita ver exactamente lo mismo. Devuelve también el % transcurrido de los
// `validez_dias` para la barra de progreso (más visible que el chip pequeño que tenía antes).
function diasRestantesInfo(cot) {
  if (cot.estado !== 'aprobada' || cotizStore.estaVencida(cot)) return null
  const dias = cotizStore.diasParaVencer(cot)
  if (dias === null) return null
  const total = cot.validez_dias || 15
  const urgencia = dias <= 2 ? 'critico' : dias <= 5 ? 'alerta' : 'normal'
  const porcentaje = Math.min(100, Math.max(0, Math.round(((total - dias) / total) * 100)))
  return { dias, total, urgencia, porcentaje, texto: dias === 0 ? 'Vence hoy' : `${dias} día${dias === 1 ? '' : 's'} restantes` }
}
// NOTA: por decisión explícita del negocio, el saldo restante de un anticipo de servicio ya NO se
// muestra en la UI (ni aquí ni en CotizacionesView.vue) — el cliente y el admin lo cuadran aparte
// por WhatsApp/privado. El registro del pago del anticipo en sí sí sigue siendo visible (ver
// pagoDeCotizacion() arriba y el badge "Anticipo de servicio" en AdminVentasView.vue).

const busqueda = ref('')
const filtroEstado = ref('')

// El backend ya trae el usuario completo anidado en cada cotización (CotizacionResponse.usuario)
// - ya no hace falta resolverlo contra MockData.usuarios, estas funciones solo formatean.
function nombreCliente(usuario) {
  return usuario ? `${usuario.nombre} ${usuario.apellido}` : 'Cliente'
}
function emailCliente(usuario) {
  return usuario?.email || ''
}
function telefonoCliente(usuario) {
  return usuario?.whatsapp || usuario?.telefono || ''
}
function linkWhatsapp(usuario, cot) {
  const tel = telefonoCliente(usuario).replace(/\D/g, '')
  if (!tel) return null
  const texto = encodeURIComponent(`Hola, te escribo sobre tu cotización ${cot.numero_cotizacion} de Acabados y Diseños 1A.`)
  return `https://wa.me/57${tel}?text=${texto}`
}
function ciudadCliente(usuario) {
  return usuario?.ciudad ? `${usuario.ciudad}, ${usuario.departamento}` : 'Sin registrar'
}

// Ubicación de ESTA solicitud (puede ser distinta de la dirección registrada del cliente) - con
// fallback al perfil para cotizaciones creadas antes de que este campo existiera.
function ubicacionSolicitud(cot) {
  return cot.ciudad ? `${cot.ciudad}, ${cot.departamento}` : ciudadCliente(cot.usuario)
}

// Cantidad pedida en un ítem de servicio, ya legible: "1 hora" / "3 horas" / "2 días".
// Los servicios por proyecto no llevan cantidad, así que ahí no se muestra nada.
function cantidadServicioTexto(s) {
  const servicio = catalog.getServiceById(s.id_servicio)
  if (!servicio) return null
  const unidad = unidadServicio(servicio)
  if (unidad === 'proyecto') return null
  const n = Number(s.cantidad)
  const base = unidad === 'hora' ? 'hora' : 'día'
  return `${Number.isInteger(n) ? n : n.toFixed(1)} ${base}${n === 1 ? '' : 's'}`
}

// Misma regla real de 3 niveles que CotizacionesView.vue (cliente): Tesalia gratis, Paicol solo
// costo de desplazamiento, cualquier otro lugar fuera de nuestro rango habitual (las dos cosas a
// la vez) - para resaltarlo de inmediato en la lista del admin sin tener que abrir el detalle.
// Solo aplica si algún ítem es un servicio (los productos van por transportadora, sin esto).
const CIUDADES_SOLO_COSTO = ['paicol']
function nivelZona(cot) {
  const ciudad = cot.ciudad || cot.usuario?.ciudad
  if (!ciudad || !(cot.servicios || []).length) return null
  const c = ciudad.trim().toLowerCase()
  if (c === 'tesalia') return 'gratis'
  if (CIUDADES_SOLO_COSTO.includes(c)) return 'costo'
  return 'evaluar'
}
function tieneCostoDesplazamiento(cot) {
  const nivel = nivelZona(cot)
  return nivel === 'costo' || nivel === 'evaluar'
}
function fueraDeRangoHabitual(cot) {
  return nivelZona(cot) === 'evaluar'
}

const listaOrdenada = computed(() =>
  [...cotizStore.cotizaciones].sort((a, b) => new Date(b.fecha) - new Date(a.fecha))
)

// "En revisión" y "Pendiente" se manejan como un solo grupo — no hay ningún paso real distinto
// entre los dos (mismo caso ya resuelto en Ventas simplificando sus 9 estados a 2): al admin no le
// aportaba nada tener que darle clic a "poner en revisión" antes de aprobar/rechazar. El valor real
// `en_revision` sigue existiendo en el ENUM por si algún dato viejo lo usa, solo que ya no hay botón
// para asignarlo de nuevo — todo pasa directo de "sin responder" a aprobada/rechazada.
function grupoEstado(estado) {
  return estado === 'en_revision' ? 'pendiente' : estado
}
function infoGrupo(estado) {
  return cotizStore.ESTADOS[grupoEstado(estado)]
}

const GRUPOS_ESTADO = [
  { key: 'pendiente', label: 'Sin responder', icon: 'ri-time-line', color: 'amarillo' },
  { key: 'aprobada', label: 'Pendiente de pago', icon: 'ri-bank-card-2-line', color: 'azul' },
  { key: 'rechazada', label: 'Rechazadas', icon: 'ri-close-circle-line', color: 'rojo' },
  { key: 'convertida_venta', label: 'Completas', icon: 'ri-trophy-line', color: 'destacado' },
]

// Antes todo llegaba mezclado en una sola lista larga y era difícil distinguir de un vistazo qué
// tipo de solicitud era cada una. Se agrupan por lo que de verdad pidió el cliente: solo productos
// (siempre precio mayorista — así se arma la cotización de producto desde el sitio, ver
// agregarProducto() en CotizacionesView.vue), producto+servicio combinados, o solo servicio.
function tipoCotizacion(cot) {
  const items = itemsDe(cot.id_cotizacion)
  const tieneProductos = items.productos.length > 0
  const tieneServicios = items.servicios.length > 0
  if (tieneProductos && tieneServicios) return 'mixta'
  if (tieneServicios) return 'servicio'
  return 'producto'
}

const TIPOS_COTIZACION = [
  { key: 'producto', label: 'Productos (mayorista)', icon: 'ri-shopping-bag-3-line' },
  { key: 'mixta', label: 'Productos + Servicios', icon: 'ri-stack-line' },
  { key: 'servicio', label: 'Servicios', icon: 'ri-tools-line' },
]
const filtroTipo = ref('')
function toggleFiltroTipo(key) {
  filtroTipo.value = filtroTipo.value === key ? '' : key
}
// Los dos conteos (por tipo y por estado) se respetan entre sí: si ya filtraste por "Productos",
// los cuadros de estado de abajo deben mostrar cuántas hay DENTRO de esa selección, no el total
// general — si no, parece que están rotos o que no tienen relación con lo que ya elegiste.
const conteoPorTipo = computed(() => {
  const conteo = { producto: 0, mixta: 0, servicio: 0 }
  cotizStore.cotizaciones
    .filter((c) => !filtroEstado.value || grupoEstado(c.estado) === filtroEstado.value)
    .forEach((c) => { conteo[tipoCotizacion(c)]++ })
  return conteo
})

const listaFiltrada = computed(() => {
  const termino = busqueda.value.toLowerCase()
  return listaOrdenada.value.filter((c) => {
    const coincideTermino =
      !termino ||
      c.numero_cotizacion.toLowerCase().includes(termino) ||
      nombreCliente(c.usuario).toLowerCase().includes(termino)
    const coincideEstado = !filtroEstado.value || grupoEstado(c.estado) === filtroEstado.value
    const coincideTipo = !filtroTipo.value || tipoCotizacion(c) === filtroTipo.value
    return coincideTermino && coincideEstado && coincideTipo
  })
})

const conteoPorGrupo = computed(() => {
  const conteo = { pendiente: 0, aprobada: 0, rechazada: 0, convertida_venta: 0 }
  cotizStore.cotizaciones
    .filter((c) => !filtroTipo.value || tipoCotizacion(c) === filtroTipo.value)
    .forEach((c) => {
      const g = grupoEstado(c.estado)
      if (conteo[g] !== undefined) conteo[g]++
    })
  return conteo
})
function toggleFiltroGrupo(key) {
  filtroEstado.value = filtroEstado.value === key ? '' : key
}

function itemsDe(id_cotizacion) {
  return cotizStore.getItemsCotizacion(id_cotizacion)
}
function nombreProducto(id_producto) {
  return catalog.getProductById(id_producto)?.nombre || 'Producto'
}
function nombreServicio(id_servicio) {
  return catalog.getServiceById(id_servicio)?.nombre_servicio || 'Servicio'
}

// ── Modal único: ver cotización + aprobar/rechazar (antes eran dos piezas separadas: el detalle
// expandible y el panel de acción aparte) ──────────────────────────────────────────────────────
const cotizacionActiva = ref(null)
const totalAjustado = ref(0)
const notaRespuesta = ref('')
const modoRechazo = ref(false)

function abrirCotizacion(cot) {
  cotizacionActiva.value = cot
  totalAjustado.value = cot.total_estimado
  notaRespuesta.value = ''
  modoRechazo.value = false
}
function cerrarModalCotizacion() {
  cotizacionActiva.value = null
}
function pedirMotivoRechazo() {
  modoRechazo.value = true
  notaRespuesta.value = ''
}
async function confirmarAprobar() {
  const cot = cotizacionActiva.value
  if (!notaRespuesta.value.trim()) {
    showToast('Escribe una nota para el cliente antes de aprobar.', 'danger')
    return
  }
  const nuevoTotal = Number(totalAjustado.value) || cot.total_estimado
  try {
    await cotizStore.actualizarEstado(cot.id_cotizacion, 'aprobada', notaRespuesta.value.trim(), nuevoTotal)
    showToast(`Cotización ${cot.numero_cotizacion} aprobada. Le enviamos al cliente el detalle y tu nota por correo.`, 'success')
    cerrarModalCotizacion()
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo aprobar la cotización. Intenta de nuevo.', 'danger')
  }
}
async function confirmarRechazar() {
  const cot = cotizacionActiva.value
  if (!notaRespuesta.value.trim()) {
    showToast('Escribe el motivo del rechazo para que el cliente lo vea.', 'danger')
    return
  }
  try {
    await cotizStore.actualizarEstado(cot.id_cotizacion, 'rechazada', notaRespuesta.value.trim())
    showToast(`Cotización ${cot.numero_cotizacion} rechazada. Le enviamos el motivo al cliente por correo.`, 'info')
    cerrarModalCotizacion()
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo rechazar la cotización. Intenta de nuevo.', 'danger')
  }
}

// ── Calendario de citas de servicio ───────────────────────────────────────
// Solo cuentan cotizaciones con al menos un servicio Y una fecha deseada (los productos no tienen
// fecha de "cita"). "Confirmado" = el cliente ya pagó su anticipo (convertida_venta); "Tentativo" =
// el admin ya fijó precio pero el cliente todavía no responde (aprobada) — puede caerse.
const vistaActiva = ref('lista') // 'lista' | 'calendario'

const citasServicio = computed(() => {
  return cotizStore.cotizaciones
    .filter((c) => ['aprobada', 'convertida_venta'].includes(c.estado))
    .map((c) => ({ cot: c, fechaIso: extraerFechaDeseada(c.observaciones), servicios: itemsDe(c.id_cotizacion).servicios }))
    .filter((c) => c.fechaIso && c.servicios.length)
})

const MESES_CALENDARIO = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre']
const hoy = new Date()
const mesActual = ref(hoy.getMonth())
const anioActual = ref(hoy.getFullYear())

function cambiarMes(delta) {
  let m = mesActual.value + delta
  let a = anioActual.value
  if (m < 0) { m = 11; a-- }
  if (m > 11) { m = 0; a++ }
  mesActual.value = m
  anioActual.value = a
}
function irAHoy() {
  mesActual.value = hoy.getMonth()
  anioActual.value = hoy.getFullYear()
}

const diasDelMes = computed(() => {
  const primerDia = new Date(anioActual.value, mesActual.value, 1)
  const ultimoDia = new Date(anioActual.value, mesActual.value + 1, 0)
  const offsetInicio = primerDia.getDay() // 0=domingo
  const dias = []
  for (let i = 0; i < offsetInicio; i++) dias.push(null)
  for (let d = 1; d <= ultimoDia.getDate(); d++) {
    const fechaIso = `${anioActual.value}-${String(mesActual.value + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`
    const citasDelDia = citasServicio.value.filter((c) => c.fechaIso === fechaIso)
    dias.push({
      numero: d,
      fechaIso,
      esHoy: fechaIso === fechaLocalISO(hoy),
      confirmadas: citasDelDia.filter((c) => c.cot.estado === 'convertida_venta'),
      tentativas: citasDelDia.filter((c) => c.cot.estado === 'aprobada'),
    })
  }
  return dias
})

// Aviso destacado: citas confirmadas (anticipo pagado) en los próximos 7 días — lo primero que se
// ve al entrar al calendario, para no tener que ir día por día buscando qué viene.
const proximasConfirmadas = computed(() => {
  const limite = new Date()
  limite.setDate(limite.getDate() + 7)
  return citasServicio.value
    .filter((c) => c.cot.estado === 'convertida_venta' && new Date(c.fechaIso + 'T00:00:00') >= new Date(fechaLocalISO(hoy) + 'T00:00:00') && new Date(c.fechaIso + 'T00:00:00') <= limite)
    .sort((a, b) => a.fechaIso.localeCompare(b.fechaIso))
})

const diaSeleccionado = ref(null)
function seleccionarDia(dia) {
  if (!dia || (!dia.confirmadas.length && !dia.tentativas.length)) return
  diaSeleccionado.value = dia
}
</script>

<template>
  <div class="admin-page-header">
    <div>
      <h1>Gestión de Cotizaciones</h1>
      <p>Revisa lo que piden los clientes, su fecha deseada y decide el estado de cada solicitud.</p>
    </div>
  </div>

      <h2 class="cotiz-seccion-titulo">¿Qué pidió el cliente?</h2>
      <div class="cotiz-tipo-tiles">
        <button
          v-for="t in TIPOS_COTIZACION"
          :key="t.key"
          type="button"
          class="cotiz-tipo-tile"
          :class="[t.key, { active: filtroTipo === t.key }]"
          @click="toggleFiltroTipo(t.key)"
        >
          <span class="cotiz-tipo-tile-icon"><i :class="t.icon"></i></span>
          <strong>{{ conteoPorTipo[t.key] }}</strong>
          <span class="cotiz-tipo-tile-label">{{ t.label }}</span>
        </button>
      </div>

      <h2 class="cotiz-seccion-titulo">Estado de la solicitud</h2>
      <div class="admin-cotiz-stats">
        <button
          v-for="g in GRUPOS_ESTADO"
          :key="g.key"
          type="button"
          class="admin-cotiz-stat"
          :class="[g.color, { active: filtroEstado === g.key }]"
          @click="toggleFiltroGrupo(g.key)"
        >
          <div class="admin-cotiz-stat-top">
            <span class="admin-cotiz-stat-icon"><i :class="g.icon"></i></span>
            <i v-if="filtroEstado === g.key" class="ri-checkbox-circle-fill admin-cotiz-stat-check"></i>
          </div>
          <strong class="admin-cotiz-stat-value">{{ conteoPorGrupo[g.key] }}</strong>
          <span class="admin-cotiz-stat-label">{{ g.label }}</span>
        </button>
      </div>

      <div class="admin-tabs">
        <button type="button" class="admin-tab" :class="{ active: vistaActiva === 'lista' }" @click="vistaActiva = 'lista'">
          <i class="ri-file-list-3-line"></i> Lista
        </button>
        <button type="button" class="admin-tab" :class="{ active: vistaActiva === 'calendario' }" @click="vistaActiva = 'calendario'">
          <i class="ri-calendar-line"></i> Calendario de citas
          <span v-if="proximasConfirmadas.length" class="badge-count">{{ proximasConfirmadas.length }}</span>
        </button>
      </div>

      <template v-if="vistaActiva === 'lista'">
      <div class="services-toolbar">
        <div class="search-input-wrap">
          <i class="ri-search-line search-input-icon"></i>
          <input v-model="busqueda" type="search" class="form-control" placeholder="Buscar por número o cliente..." style="padding-left:38px;" />
        </div>
        <select v-model="filtroEstado">
          <option value="">Todos los estados</option>
          <option v-for="g in GRUPOS_ESTADO" :key="g.key" :value="g.key">{{ g.label }}</option>
        </select>
      </div>

      <p v-if="!listaFiltrada.length" class="text-muted text-center" style="padding:60px 0;">No hay cotizaciones que coincidan.</p>

      <div v-for="cot in listaFiltrada" :key="cot.id_cotizacion" class="cotiz-card">
        <div class="cotiz-card-header">
          <div>
            <div class="cotiz-card-numero">
              {{ cot.numero_cotizacion }}
              <span class="cotiz-tipo-tag" :class="tipoCotizacion(cot)">{{ TIPOS_COTIZACION.find(t => t.key === tipoCotizacion(cot)).label }}</span>
            </div>
            <div class="cotiz-card-fecha">{{ formatDateTime(cot.fecha) }} &middot; {{ nombreCliente(cot.usuario) }}</div>
            <div class="cotiz-card-contacto-info">
              <span><i class="ri-phone-line"></i> {{ telefonoCliente(cot.usuario) || 'Sin teléfono' }}</span>
              <span><i class="ri-mail-line"></i> {{ emailCliente(cot.usuario) }}</span>
            </div>
          </div>
          <span v-if="cotizStore.estaVencida(cot)" class="badge badge-gray"><i class="ri-time-line"></i> Vencida</span>
          <span v-else class="badge" :class="infoGrupo(cot.estado).badge">
            <i :class="infoGrupo(cot.estado).icon"></i> {{ infoGrupo(cot.estado).label }}
          </span>
        </div>

        <div v-if="fueraDeRangoHabitual(cot)" class="cotiz-fecha-destacada cotiz-zona-destacada">
          <i class="ri-map-pin-range-line"></i>
          <div><span>Fuera de nuestro rango habitual</span><strong>Evaluar si se puede atender</strong></div>
        </div>

        <div v-if="tieneCostoDesplazamiento(cot)" class="cotiz-fecha-destacada cotiz-zona-destacada">
          <i class="ri-route-line"></i>
          <div><span>Tiene costo de desplazamiento</span><strong>Coordinar valor directamente con el cliente</strong></div>
        </div>

        <div v-if="extraerFechaDeseada(cot.observaciones)" class="cotiz-fecha-destacada">
          <i class="ri-calendar-event-fill"></i>
          <div><span>Fecha elegida por el cliente</span><strong>{{ formatDate(extraerFechaDeseada(cot.observaciones)) }}</strong></div>
        </div>

        <div v-if="diasRestantesInfo(cot)" class="cotiz-validez-progreso" :class="diasRestantesInfo(cot).urgencia">
          <div class="cotiz-validez-progreso-top">
            <span><i class="ri-alarm-warning-line"></i> Validez de la aprobación</span>
            <strong>{{ diasRestantesInfo(cot).texto }}</strong>
          </div>
          <div class="cotiz-validez-progreso-bar"><div class="cotiz-validez-progreso-fill" :style="{ width: diasRestantesInfo(cot).porcentaje + '%' }"></div></div>
        </div>

        <div class="cotiz-card-meta">
          <div class="cotiz-card-meta-item destacado"><span>Total estimado</span><strong>{{ formatCOP(cot.total_estimado) }}</strong></div>
          <div class="cotiz-card-meta-item"><span>Ubicación de la solicitud</span><strong>{{ ubicacionSolicitud(cot) }}</strong></div>
          <div class="cotiz-card-meta-item"><span>Validez</span><strong>{{ cot.validez_dias }} días</strong></div>
        </div>

        <button type="button" class="btn btn-primary btn-block" style="margin-top:14px;" @click="abrirCotizacion(cot)">
          <i class="ri-eye-line"></i> Ver Cotización
        </button>
      </div>
      </template>

      <!-- CALENDARIO DE CITAS -->
      <template v-else>
        <div v-if="proximasConfirmadas.length" class="cotiz-aviso-proximas">
          <i class="ri-alarm-warning-fill"></i>
          <div>
            <strong>Tienes {{ proximasConfirmadas.length }} cita{{ proximasConfirmadas.length === 1 ? '' : 's' }} confirmada{{ proximasConfirmadas.length === 1 ? '' : 's' }} en los próximos 7 días</strong>
            <div v-for="c in proximasConfirmadas" :key="c.cot.id_cotizacion" class="cotiz-aviso-item">
              {{ formatDate(c.fechaIso) }} — {{ nombreCliente(c.cot.usuario) }}: {{ nombreServicio(c.servicios[0].id_servicio) }}
            </div>
          </div>
        </div>

        <div class="cotiz-cal-card">
          <div class="cotiz-cal-header">
            <div class="cotiz-cal-nav">
              <button type="button" class="cotiz-cal-nav-btn" @click="cambiarMes(-1)"><i class="ri-arrow-left-s-line"></i></button>
              <h2>{{ MESES_CALENDARIO[mesActual] }} {{ anioActual }}</h2>
              <button type="button" class="cotiz-cal-nav-btn" @click="cambiarMes(1)"><i class="ri-arrow-right-s-line"></i></button>
            </div>
            <button type="button" class="cotiz-cal-hoy-btn" @click="irAHoy"><i class="ri-calendar-check-line"></i> Hoy</button>
          </div>

          <div class="cotiz-cal-legend">
            <span><i class="cotiz-cal-dot confirmada"></i> Confirmada (anticipo pagado)</span>
            <span><i class="cotiz-cal-dot tentativa"></i> Tentativa (esperando respuesta)</span>
          </div>

          <div class="cotiz-cal-grid">
            <div class="cotiz-cal-diasemana" v-for="d in ['Dom','Lun','Mar','Mié','Jue','Vie','Sáb']" :key="d">{{ d }}</div>
            <div
              v-for="(dia, i) in diasDelMes"
              :key="i"
              class="cotiz-cal-dia"
              :class="{ vacio: !dia, hoy: dia?.esHoy, seleccionable: dia && (dia.confirmadas.length || dia.tentativas.length) }"
              @click="dia && seleccionarDia(dia)"
            >
              <template v-if="dia">
                <span class="cotiz-cal-numero">{{ dia.numero }}</span>
                <div v-if="dia.confirmadas.length || dia.tentativas.length" class="cotiz-cal-pills">
                  <span v-if="dia.confirmadas.length" class="cotiz-cal-pill confirmada">{{ dia.confirmadas.length }}</span>
                  <span v-if="dia.tentativas.length" class="cotiz-cal-pill tentativa">{{ dia.tentativas.length }}</span>
                </div>
              </template>
            </div>
          </div>
        </div>
      </template>

  <!-- Modal: ver / aprobar / rechazar una cotización -->
  <Transition name="confirm-modal-fade">
    <div v-if="cotizacionActiva" class="confirm-modal-overlay" @click.self="cerrarModalCotizacion">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal producto-modal cotiz-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="cerrarModalCotizacion"><i class="ri-close-line"></i></button>

          <div class="cotiz-modal-header">
            <div>
              <h3 class="confirm-modal-title" style="margin-bottom:2px;text-align:left;">{{ cotizacionActiva.numero_cotizacion }}</h3>
              <span class="cotiz-modal-subfecha">Solicitada el {{ formatDateTime(cotizacionActiva.fecha) }}</span>
            </div>
            <span v-if="cotizStore.estaVencida(cotizacionActiva)" class="badge badge-gray"><i class="ri-time-line"></i> Vencida</span>
            <span v-else class="badge" :class="infoGrupo(cotizacionActiva.estado).badge">
              <i :class="infoGrupo(cotizacionActiva.estado).icon"></i> {{ infoGrupo(cotizacionActiva.estado).label }}
            </span>
          </div>

          <div class="cotiz-modal-cliente">
            <h4><i class="ri-user-3-line"></i> Información del cliente</h4>
            <p class="cotiz-modal-nombre">{{ nombreCliente(cotizacionActiva.usuario) }}</p>
            <p><i class="ri-phone-line"></i> {{ telefonoCliente(cotizacionActiva.usuario) || 'Sin teléfono registrado' }}</p>
            <p><i class="ri-mail-line"></i> {{ emailCliente(cotizacionActiva.usuario) }}</p>
            <div class="cotiz-card-contacto">
              <a v-if="telefonoCliente(cotizacionActiva.usuario)" :href="`tel:${telefonoCliente(cotizacionActiva.usuario)}`" class="cotiz-contacto-btn"><i class="ri-phone-line"></i> Llamar</a>
              <a v-if="linkWhatsapp(cotizacionActiva.usuario, cotizacionActiva)" :href="linkWhatsapp(cotizacionActiva.usuario, cotizacionActiva)" target="_blank" rel="noopener" class="cotiz-contacto-btn whatsapp"><i class="ri-whatsapp-line"></i> WhatsApp</a>
              <a :href="`mailto:${emailCliente(cotizacionActiva.usuario)}`" class="cotiz-contacto-btn"><i class="ri-mail-line"></i> Correo</a>
            </div>
          </div>

          <div class="cotiz-fecha-destacada cotiz-ubicacion-destacada">
            <i class="ri-map-pin-2-fill"></i>
            <div><span>Ubicación de la solicitud</span><strong>{{ ubicacionSolicitud(cotizacionActiva) }}</strong></div>
          </div>

          <div v-if="extraerFechaDeseada(cotizacionActiva.observaciones)" class="cotiz-fecha-destacada">
            <i class="ri-calendar-event-fill"></i>
            <div><span>Fecha elegida por el cliente</span><strong>{{ formatDate(extraerFechaDeseada(cotizacionActiva.observaciones)) }}</strong></div>
          </div>

          <!-- Aclara la diferencia entre "aprobada" (el admin ya puso precio, el cliente todavía
               no responde ni paga) y "convertida a venta" (el cliente ya pagó el anticipo y la
               cita queda confirmada) — para no confundir aprobar con que ya está pagado. -->
          <div v-if="tipoCotizacion(cotizacionActiva) !== 'producto'">
            <div v-if="cotizacionActiva.estado === 'aprobada' && !cotizStore.estaVencida(cotizacionActiva)" class="cotiz-estado-aviso pendiente-pago">
              <i class="ri-time-line"></i> Aprobada por ti — todavía falta que el cliente pague el anticipo del 50% para que la cita quede confirmada. Por ahora aparece como "tentativa" en el calendario.
            </div>
            <div v-else-if="cotizacionActiva.estado === 'convertida_venta'" class="cotiz-estado-aviso pagado">
              <i class="ri-checkbox-circle-fill"></i> El cliente ya pagó el anticipo — la cita quedó confirmada en el calendario.
              <template v-if="pagoDeCotizacion(cotizacionActiva.id_cotizacion)">
                Pagó {{ formatCOP(pagoDeCotizacion(cotizacionActiva.id_cotizacion).pago.valor) }} por {{ pagoDeCotizacion(cotizacionActiva.id_cotizacion).pago.metodo_pago }} el {{ formatDateTime(pagoDeCotizacion(cotizacionActiva.id_cotizacion).pago.fecha) }}.
              </template>
            </div>
          </div>

          <div v-if="diasRestantesInfo(cotizacionActiva)" class="cotiz-validez-progreso" :class="diasRestantesInfo(cotizacionActiva).urgencia">
            <div class="cotiz-validez-progreso-top">
              <span><i class="ri-alarm-warning-line"></i> Validez de la aprobación</span>
              <strong>{{ diasRestantesInfo(cotizacionActiva).texto }}</strong>
            </div>
            <div class="cotiz-validez-progreso-bar"><div class="cotiz-validez-progreso-fill" :style="{ width: diasRestantesInfo(cotizacionActiva).porcentaje + '%' }"></div></div>
          </div>

          <h4 style="margin-top:16px;">Productos y servicios solicitados</h4>
          <div v-for="p in itemsDe(cotizacionActiva.id_cotizacion).productos" :key="'p' + p.id_detalle" class="cotiz-detail-item">
            <img :src="catalog.getProductById(p.id_producto)?.imagen_url" :alt="nombreProducto(p.id_producto)" />
            <span>
              <span class="cotiz-item-kicker cotiz-item-kicker-producto"><i class="ri-shopping-bag-line"></i> Producto</span><br />
              {{ nombreProducto(p.id_producto) }}<span class="cotiz-detail-item-meta">x{{ p.cantidad }}</span>
            </span>
            <span>{{ formatCOP(p.subtotal) }}</span>
          </div>
          <div v-for="s in itemsDe(cotizacionActiva.id_cotizacion).servicios" :key="'s' + s.id_detalle" class="cotiz-detail-item">
            <img :src="catalog.getServiceById(s.id_servicio)?.imagen_url" :alt="nombreServicio(s.id_servicio)" />
            <span>
              <span class="cotiz-item-kicker cotiz-item-kicker-servicio"><i class="ri-tools-line"></i> Servicio</span><br />
              {{ nombreServicio(s.id_servicio) }}<span v-if="cantidadServicioTexto(s)" class="cotiz-detail-item-meta">{{ cantidadServicioTexto(s) }}</span>
            </span>
            <span>{{ formatCOP(s.subtotal) }}</span>
          </div>

          <p v-if="extraerNotaCliente(cotizacionActiva.observaciones)" class="cotiz-card-obs"><strong>Observación del cliente:</strong> {{ extraerNotaCliente(cotizacionActiva.observaciones) }}</p>
          <p v-if="cotizacionActiva.respuesta" class="cotiz-card-obs"><strong>Tu respuesta:</strong> {{ cotizacionActiva.respuesta }}</p>

          <!-- Solo editable mientras sigue sin responder -->
          <template v-if="grupoEstado(cotizacionActiva.estado) === 'pendiente' && !cotizStore.estaVencida(cotizacionActiva)">
            <template v-if="!modoRechazo">
              <div class="form-group" style="margin:16px 0 8px;">
                <label class="form-label">Total final a aprobar</label>
                <input v-model.number="totalAjustado" type="number" min="0" class="form-control" />
              </div>
              <p class="cotiz-extras-warning">
                <i class="ri-error-warning-line"></i> Este precio es un estimado, no fijo — si el trabajo termina necesitando más materiales o tiempo del previsto, puede generar costos extra. Acláraselo al cliente por WhatsApp antes de confirmar.
              </p>
              <div class="form-group" style="margin-bottom:16px;">
                <label class="form-label required">Nota para el cliente</label>
                <textarea v-model="notaRespuesta" class="form-control" rows="2" placeholder="Ej: Confirmamos la fecha e incluye materiales." required></textarea>
              </div>
              <div class="confirm-modal-actions">
                <button class="btn btn-sm" style="background:var(--danger);color:white;" @click="pedirMotivoRechazo"><i class="ri-close-circle-line"></i> Rechazar</button>
                <button class="btn btn-primary btn-sm" @click="confirmarAprobar"><i class="ri-checkbox-circle-line"></i> Aprobar con este precio</button>
              </div>
            </template>
            <template v-else>
              <div class="form-group" style="margin:16px 0;">
                <label class="form-label required">Motivo del rechazo</label>
                <textarea v-model="notaRespuesta" class="form-control" rows="2" placeholder="Cuéntale al cliente por qué, y si aplica, cuándo podría reprogramar."></textarea>
              </div>
              <div class="confirm-modal-actions">
                <button class="btn btn-outline-red btn-sm" @click="modoRechazo = false">Volver</button>
                <button class="btn btn-sm" style="background:var(--danger);color:white;" @click="confirmarRechazar"><i class="ri-close-circle-line"></i> Confirmar rechazo</button>
              </div>
            </template>
          </template>
          <div v-else class="cotiz-detail-total" style="margin-top:14px;"><span>Total</span><span>{{ formatCOP(cotizacionActiva.total_estimado) }}</span></div>
        </div>
      </Transition>
    </div>
  </Transition>

  <!-- Detalle del día seleccionado en el calendario -->
  <Transition name="confirm-modal-fade">
    <div v-if="diaSeleccionado" class="confirm-modal-overlay" @click.self="diaSeleccionado = null">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal producto-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="diaSeleccionado = null"><i class="ri-close-line"></i></button>
          <h3 class="confirm-modal-title">{{ formatDate(diaSeleccionado.fechaIso) }}</h3>

          <div v-for="c in diaSeleccionado.confirmadas" :key="'c' + c.cot.id_cotizacion" class="cotiz-cita-detalle confirmada">
            <div class="cotiz-cita-detalle-header">
              <span class="badge badge-green"><i class="ri-checkbox-circle-fill"></i> Confirmada</span>
              <strong>{{ c.cot.numero_cotizacion }}</strong>
            </div>
            <p>
              <strong>{{ nombreCliente(c.cot.usuario) }}</strong> — {{ nombreServicio(c.servicios[0].id_servicio) }}
              <span v-if="itemsDe(c.cot.id_cotizacion).productos.length" class="cotiz-tipo-tag mixta">+ {{ itemsDe(c.cot.id_cotizacion).productos.length }} producto{{ itemsDe(c.cot.id_cotizacion).productos.length > 1 ? 's' : '' }}</span>
            </p>
            <p class="cotiz-cita-detalle-meta">{{ formatCOP(c.cot.total_estimado) }} total &middot; {{ telefonoCliente(c.cot.usuario) }}</p>
            <div class="cotiz-card-contacto">
              <a v-if="telefonoCliente(c.cot.usuario)" :href="`tel:${telefonoCliente(c.cot.usuario)}`" class="cotiz-contacto-btn"><i class="ri-phone-line"></i> Llamar</a>
              <a v-if="linkWhatsapp(c.cot.usuario, c.cot)" :href="linkWhatsapp(c.cot.usuario, c.cot)" target="_blank" rel="noopener" class="cotiz-contacto-btn whatsapp"><i class="ri-whatsapp-line"></i> WhatsApp</a>
              <button type="button" class="cotiz-contacto-btn" @click="diaSeleccionado = null; abrirCotizacion(c.cot)"><i class="ri-eye-line"></i> Ver cotización</button>
            </div>
          </div>

          <div v-for="c in diaSeleccionado.tentativas" :key="'t' + c.cot.id_cotizacion" class="cotiz-cita-detalle tentativa">
            <div class="cotiz-cita-detalle-header">
              <span class="badge badge-yellow"><i class="ri-time-line"></i> Tentativa</span>
              <strong>{{ c.cot.numero_cotizacion }}</strong>
            </div>
            <p>
              <strong>{{ nombreCliente(c.cot.usuario) }}</strong> — {{ nombreServicio(c.servicios[0].id_servicio) }}
              <span v-if="itemsDe(c.cot.id_cotizacion).productos.length" class="cotiz-tipo-tag mixta">+ {{ itemsDe(c.cot.id_cotizacion).productos.length }} producto{{ itemsDe(c.cot.id_cotizacion).productos.length > 1 ? 's' : '' }}</span>
            </p>
            <p class="cotiz-cita-detalle-meta">{{ formatCOP(c.cot.total_estimado) }} total &middot; el cliente todavía no confirma ni paga</p>
            <div class="cotiz-card-contacto">
              <a v-if="telefonoCliente(c.cot.usuario)" :href="`tel:${telefonoCliente(c.cot.usuario)}`" class="cotiz-contacto-btn"><i class="ri-phone-line"></i> Llamar</a>
              <a v-if="linkWhatsapp(c.cot.usuario, c.cot)" :href="linkWhatsapp(c.cot.usuario, c.cot)" target="_blank" rel="noopener" class="cotiz-contacto-btn whatsapp"><i class="ri-whatsapp-line"></i> WhatsApp</a>
              <button type="button" class="cotiz-contacto-btn" @click="diaSeleccionado = null; abrirCotizacion(c.cot)"><i class="ri-eye-line"></i> Ver cotización</button>
            </div>
          </div>
        </div>
      </Transition>
    </div>
  </Transition>
</template>

<style scoped>
.cotiz-seccion-titulo { font-family: var(--font-main); font-weight: 700; font-size: 0.85rem; color: var(--text-light); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 12px; }

.cotiz-tipo-tiles { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 28px; }
.cotiz-tipo-tile {
  display: flex; flex-direction: column; align-items: center; gap: 10px; background: white;
  border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 26px 18px;
  cursor: pointer; transition: var(--transition); font-family: inherit; position: relative; overflow: hidden;
}
.cotiz-tipo-tile::before {
  content: ''; position: absolute; inset: 0; opacity: 0; transition: var(--transition);
  background: linear-gradient(160deg, rgba(192,57,43,0.06), transparent 60%);
}
.cotiz-tipo-tile:hover::before, .cotiz-tipo-tile.active::before { opacity: 1; }
.cotiz-tipo-tile-icon {
  width: 52px; height: 52px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  background: rgba(192,57,43,0.1); color: var(--primary); font-size: 1.5rem; transition: var(--transition);
}
.cotiz-tipo-tile.producto .cotiz-tipo-tile-icon { background: rgba(41,128,185,0.1); color: var(--info); }
.cotiz-tipo-tile.servicio .cotiz-tipo-tile-icon { background: rgba(243,156,18,0.14); color: #9a6208; }
.cotiz-tipo-tile strong { font-family: var(--font-main); font-weight: 800; font-size: 1.9rem; color: var(--secondary); line-height: 1; }
.cotiz-tipo-tile-label { font-size: 0.82rem; font-weight: 600; color: var(--text-light); text-align: center; }
.cotiz-tipo-tile:hover { transform: translateY(-4px); box-shadow: var(--shadow-lg); }
.cotiz-tipo-tile.active { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(192,57,43,0.15); }
.cotiz-tipo-tile.producto.active { border-color: var(--info); box-shadow: 0 0 0 3px rgba(41,128,185,0.15); }
.cotiz-tipo-tile.servicio.active { border-color: var(--warning); box-shadow: 0 0 0 3px rgba(243,156,18,0.2); }
.cotiz-tipo-tile.active .cotiz-tipo-tile-icon { transform: scale(1.08); }

.cotiz-tipo-tag {
  display: inline-block; font-size: 0.62rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.3px;
  padding: 2px 8px; border-radius: 20px; margin-left: 8px; vertical-align: middle;
}
.cotiz-tipo-tag.producto { background: rgba(41,128,185,0.12); color: var(--info); }
.cotiz-tipo-tag.mixta { background: rgba(192,57,43,0.1); color: var(--primary); }
.cotiz-tipo-tag.servicio { background: rgba(243,156,18,0.14); color: #9a6208; }

.admin-cotiz-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 18px; margin-bottom: 24px; }
.admin-cotiz-stat {
  background: white; border: 1px solid var(--border); border-radius: var(--radius-lg);
  padding: 20px; text-align: left; cursor: pointer; transition: var(--transition); font-family: inherit;
  display: flex; flex-direction: column;
}
.admin-cotiz-stat-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.admin-cotiz-stat-icon {
  width: 42px; height: 42px; border-radius: var(--radius-sm); display: flex; align-items: center;
  justify-content: center; font-size: 1.2rem; background: var(--off-white); color: var(--text-muted); transition: var(--transition);
}
.admin-cotiz-stat-check { font-size: 1.2rem; color: currentColor; }
.admin-cotiz-stat-value { display: block; font-family: var(--font-main); font-weight: 800; font-size: 1.6rem; color: var(--secondary); margin-bottom: 2px; }
.admin-cotiz-stat-label { font-size: 0.82rem; color: var(--text-muted); font-weight: 600; }
.admin-cotiz-stat:hover { transform: translateY(-3px); box-shadow: var(--shadow-lg); }
.admin-cotiz-stat.active { border-color: currentColor; box-shadow: 0 0 0 3px rgba(0,0,0,0.06); }

.admin-cotiz-stat.amarillo .admin-cotiz-stat-icon { background: rgba(243,156,18,0.12); color: #B8790A; }
.admin-cotiz-stat.amarillo.active { color: #B8790A; }
.admin-cotiz-stat.azul .admin-cotiz-stat-icon { background: rgba(41,128,185,0.12); color: var(--info); }
.admin-cotiz-stat.azul.active { color: var(--info); }
.admin-cotiz-stat.rojo .admin-cotiz-stat-icon { background: rgba(192,57,43,0.1); color: var(--primary); }
.admin-cotiz-stat.rojo.active { color: var(--primary); }

/* "Completas" (convertida_venta) es la meta final de toda cotización — el cliente ya pagó. Se
   destaca sólida en verde en vez de con apenas un tinte pastel como las demás, para que se note
   que es el logro final, no solo un estado más. */
.admin-cotiz-stat.destacado {
  background: linear-gradient(135deg, #1e7e42 0%, var(--success) 100%);
  border-color: transparent;
}
.admin-cotiz-stat.destacado .admin-cotiz-stat-icon { background: rgba(255,255,255,0.18); color: white; }
.admin-cotiz-stat.destacado .admin-cotiz-stat-value, .admin-cotiz-stat.destacado .admin-cotiz-stat-label { color: white; }
.admin-cotiz-stat.destacado .admin-cotiz-stat-label { opacity: 0.9; }
.admin-cotiz-stat.destacado.active { box-shadow: 0 0 0 3px rgba(255,255,255,0.4); }
.admin-cotiz-stat.destacado .admin-cotiz-stat-check { color: white; }

.cotiz-card-contacto-info { display: flex; gap: 16px; margin-top: 6px; flex-wrap: wrap; }
.cotiz-card-contacto-info span { display: inline-flex; align-items: center; gap: 5px; font-size: 0.8rem; color: var(--text-light); }
.cotiz-card-contacto-info i { color: var(--text-muted); }

.cotiz-fecha-destacada {
  display: flex; align-items: center; gap: 12px; background: linear-gradient(135deg, rgba(192,57,43,0.08), rgba(192,57,43,0.03));
  border: 1px solid rgba(192,57,43,0.25); border-radius: var(--radius); padding: 12px 16px; margin: 12px 0;
}
.cotiz-fecha-destacada > i { font-size: 1.5rem; color: var(--primary); flex-shrink: 0; }
.cotiz-fecha-destacada span { display: block; font-size: 0.72rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.4px; }
.cotiz-fecha-destacada strong { display: block; font-family: var(--font-main); font-weight: 800; font-size: 1.05rem; color: var(--secondary); }

/* Misma tarjeta destacada, en ámbar en vez de rojo - es una advertencia (fuera de zona), no un
   dato neutro como la fecha elegida. */
.cotiz-zona-destacada { background: linear-gradient(135deg, rgba(243,156,18,0.1), rgba(243,156,18,0.03)); border-color: rgba(243,156,18,0.35); }
.cotiz-zona-destacada > i { color: var(--warning); }

/* Ubicación de la solicitud - misma tarjeta, en azul, para distinguirla de la fecha cuando van
   apiladas y porque es el dato que define si el servicio se puede atender. */
.cotiz-ubicacion-destacada { background: linear-gradient(135deg, rgba(41,128,185,0.1), rgba(41,128,185,0.03)); border-color: rgba(41,128,185,0.3); }
.cotiz-ubicacion-destacada > i { color: #2980b9; }

.cotiz-modal { text-align: left; }
.cotiz-modal-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.cotiz-modal-subfecha { font-size: 0.78rem; color: var(--text-muted); }
.cotiz-modal-cliente { background: var(--off-white); border-radius: var(--radius); padding: 14px 16px; margin-bottom: 4px; }
.cotiz-modal-cliente h4 { display: flex; align-items: center; gap: 6px; font-family: var(--font-main); font-weight: 700; font-size: 0.82rem; color: var(--secondary); margin-bottom: 8px; }
.cotiz-modal-nombre { font-family: var(--font-main); font-weight: 700; color: var(--secondary); margin-bottom: 4px; }
.cotiz-modal-cliente p { font-size: 0.85rem; color: var(--text); margin: 3px 0; display: flex; align-items: center; gap: 6px; }
.cotiz-modal-cliente p i { color: var(--text-muted); width: 16px; }

.cotiz-card-contacto { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.cotiz-contacto-btn {
  display: inline-flex; align-items: center; gap: 5px; font-size: 0.75rem; font-weight: 600;
  padding: 5px 11px; border-radius: 20px; background: var(--off-white); color: var(--text-light); transition: var(--transition);
}
.cotiz-contacto-btn:hover { background: var(--primary); color: white; }
.cotiz-contacto-btn.whatsapp:hover { background: #25D366; }

.cotiz-extras-warning {
  display: flex; align-items: flex-start; gap: 8px; font-size: 0.8rem; color: var(--secondary);
  background: rgba(243,156,18,0.1); border: 1px solid rgba(243,156,18,0.3); border-radius: var(--radius-sm);
  padding: 10px 12px; margin-bottom: 12px;
}
.cotiz-extras-warning i { color: var(--warning); flex-shrink: 0; margin-top: 1px; }

.cotiz-estado-aviso {
  display: flex; align-items: flex-start; gap: 8px; font-size: 0.83rem; border-radius: var(--radius-sm);
  padding: 12px 14px; margin: 12px 0; line-height: 1.4;
}
.cotiz-estado-aviso i { flex-shrink: 0; margin-top: 1px; font-size: 1.1rem; }
.cotiz-estado-aviso.pendiente-pago { background: rgba(41,128,185,0.1); color: #1c5a80; }
.cotiz-estado-aviso.pendiente-pago i { color: var(--info); }
.cotiz-estado-aviso.pagado { background: rgba(39,174,96,0.1); color: #1d7a45; }
.cotiz-estado-aviso.pagado i { color: var(--success); }

.cotiz-aviso-proximas {
  display: flex; align-items: flex-start; gap: 14px; background: rgba(192,57,43,0.06); border: 1px solid rgba(192,57,43,0.25);
  border-radius: var(--radius-lg); padding: 18px 20px; margin-bottom: 20px;
}
.cotiz-aviso-proximas > i { font-size: 1.6rem; color: var(--primary); flex-shrink: 0; margin-top: 2px; }
.cotiz-aviso-proximas strong { display: block; font-family: var(--font-main); color: var(--secondary); margin-bottom: 8px; }
.cotiz-aviso-item { font-size: 0.85rem; color: var(--text); padding: 4px 0; }

.cotiz-cal-card { background: white; border-radius: var(--radius-lg); box-shadow: var(--shadow-lg); overflow: hidden; }
.cotiz-cal-header {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
  background: linear-gradient(135deg, var(--secondary) 0%, #2a2a4a 100%); padding: 20px 24px;
}
.cotiz-cal-nav { display: flex; align-items: center; gap: 14px; }
.cotiz-cal-nav h2 { font-family: var(--font-main); font-weight: 800; font-size: 1.15rem; color: white; min-width: 170px; text-align: center; }
.cotiz-cal-nav-btn {
  width: 34px; height: 34px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  background: rgba(255,255,255,0.1); color: white; font-size: 1.1rem; transition: var(--transition);
}
.cotiz-cal-nav-btn:hover { background: rgba(255,255,255,0.2); }
.cotiz-cal-hoy-btn {
  display: inline-flex; align-items: center; gap: 6px; background: var(--primary); color: white;
  font-family: var(--font-main); font-weight: 700; font-size: 0.8rem; padding: 8px 16px; border-radius: 20px;
  transition: var(--transition);
}
.cotiz-cal-hoy-btn:hover { background: var(--primary-dark); }

.cotiz-cal-legend { display: flex; gap: 20px; padding: 14px 24px; border-bottom: 1px solid var(--border); font-size: 0.78rem; color: var(--text-muted); flex-wrap: wrap; }
.cotiz-cal-legend span { display: inline-flex; align-items: center; gap: 6px; }
.cotiz-cal-dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
.cotiz-cal-dot.confirmada { background: var(--success); }
.cotiz-cal-dot.tentativa { background: var(--accent); }

.cotiz-cal-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 8px; padding: 20px 24px 24px; }
.cotiz-cal-diasemana { text-align: center; font-family: var(--font-main); font-weight: 700; font-size: 0.7rem; color: var(--text-muted); text-transform: uppercase; padding-bottom: 8px; }
.cotiz-cal-dia {
  min-height: 76px; border-radius: var(--radius); background: var(--off-white);
  padding: 10px; display: flex; flex-direction: column; align-items: center; gap: 6px; transition: var(--transition);
}
.cotiz-cal-dia.vacio { background: transparent; }
.cotiz-cal-dia.hoy { background: rgba(192,57,43,0.08); box-shadow: inset 0 0 0 2px var(--primary); }
.cotiz-cal-dia.seleccionable { cursor: pointer; background: white; box-shadow: 0 1px 4px rgba(26,26,46,0.06); }
.cotiz-cal-dia.seleccionable:hover { transform: translateY(-3px) scale(1.03); box-shadow: 0 10px 22px rgba(26,26,46,0.14); }
.cotiz-cal-numero { font-family: var(--font-main); font-weight: 700; font-size: 0.85rem; color: var(--secondary); }
.cotiz-cal-pills { display: flex; gap: 4px; flex-wrap: wrap; justify-content: center; }
.cotiz-cal-pill {
  min-width: 20px; height: 20px; padding: 0 6px; border-radius: 20px; font-size: 0.68rem; font-weight: 800;
  display: flex; align-items: center; justify-content: center;
}
.cotiz-cal-pill.confirmada { background: var(--success); color: white; }
.cotiz-cal-pill.tentativa { background: var(--accent); color: var(--secondary); }

.cotiz-cita-detalle { text-align: left; border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 14px; margin-bottom: 12px; }
.cotiz-cita-detalle.confirmada { border-left: 4px solid var(--success); }
.cotiz-cita-detalle.tentativa { border-left: 4px solid var(--warning); }
.cotiz-cita-detalle-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.cotiz-cita-detalle p { font-size: 0.85rem; color: var(--text); margin: 2px 0; }
.cotiz-cita-detalle-meta { color: var(--text-muted) !important; font-size: 0.78rem !important; }

@media (max-width: 768px) {
  .admin-cotiz-stats { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .cotiz-tipo-tiles { grid-template-columns: 1fr; }
  .cotiz-cal-grid { gap: 4px; padding: 14px; }
  .cotiz-cal-dia { min-height: 56px; padding: 6px; }
  .cotiz-cal-header { padding: 16px; }
  .cotiz-cal-legend { padding: 12px 16px; }
}
</style>
