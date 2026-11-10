<script setup>
// Sin RF oficial (ampliación del alcance, ver docs/MATRIZ_TRAZABILIDAD.md) - cotizaciones: solicitar (productos y/o servicios) e historial con estado -> tablas
// `cotizaciones`, `cotizacion_productos`, `cotizacion_servicios`
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCatalogStore } from '../stores/catalog'
import { useCotizacionesStore, unidadServicio, tarifaServicio, ADVERTENCIA_MODO, PORCENTAJE_ANTICIPO, montoAnticipo } from '../stores/cotizaciones'
import { useCartStore } from '../stores/cart'
import { useVentasStore } from '../stores/ventas'
import { useToast } from '../composables/useToast'
import { formatCOP, formatDate, empacarFechaDeseada, extraerFechaDeseada, extraerNotaCliente, fechaLocalISO } from '../composables/useFormat'
import { DEPARTAMENTOS, MUNICIPIOS_POR_DEPARTAMENTO } from '../data/colombia'
import CotizarLoginModal from '../components/service/CotizarLoginModal.vue'

// Nombre explícito requerido por el <keep-alive :include="['CotizacionesView']"> de App.vue,
// para que el borrador de "Nueva cotización" sobreviva a una salida a Términos y vuelta.
defineOptions({ name: 'CotizacionesView' })

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const catalog = useCatalogStore()
const cotizStore = useCotizacionesStore()
const cart = useCartStore()
const ventasStore = useVentasStore()
const { showToast } = useToast()

// Aviso de sesión: aparece de inmediato si no hay usuario autenticado.
const mostrarModalLogin = ref(!auth.isAuthenticated)
function irALogin() {
  mostrarModalLogin.value = false
  router.push({ path: '/login', query: { redirect: '/cotizaciones' } })
}

// Cotizaciones es información privada (ya no se precarga en App.vue como productos/servicios) -
// se pide aquí una sola vez; el <keep-alive> de esta vista no vuelve a disparar onMounted en
// navegaciones internas posteriores, lo cual está bien porque crearCotizacion/actualizarEstado
// ya resincronizan solos después de cada acción.
onMounted(() => {
  if (auth.isAuthenticated) cotizStore.cargarCotizaciones()
})

// ── Borrador de "Nueva cotización" ──────────────────────────────────────────
// Se guarda en localStorage (por usuario) para que un refresh accidental de la página, o cerrar
// y volver a abrir el navegador, no borre lo que el cliente ya llevaba armado — no solo la salida
// a Términos y vuelta (eso ya lo cubre el <keep-alive>, que sigue siendo útil para no perder
// paneles abiertos/scroll en esa navegación puntual).
function claveBorrador() {
  return `acabados1a_cotizacion_borrador_${auth.usuario?.id_usuario || 'anon'}`
}
function cargarBorrador() {
  try {
    return JSON.parse(localStorage.getItem(claveBorrador()) || 'null')
  } catch {
    return null
  }
}
const borradorInicial = cargarBorrador()

const tabActiva = ref(borradorInicial?.tabActiva || 'mis-cotizaciones')

// Al entrar a "Mis Cotizaciones" (por click en la pestaña o por el aviso de "cambió de estado"),
// se marcan como vistas todas las que hoy están aprobadas/rechazadas - mismo criterio que
// PqrsView.vue con su pestaña "historial".
watch(
  tabActiva,
  (v) => { if (v === 'mis-cotizaciones' && auth.usuario) cotizStore.marcarCotizacionesVistas(auth.usuario.id_usuario) },
  { immediate: true }
)

const busquedaCotizaciones = ref('')
const filtroEstado = ref('')

const misCotizaciones = computed(() => {
  if (!auth.usuario) return []
  const termino = busquedaCotizaciones.value.toLowerCase()
  return cotizStore.getCotizacionesDeUsuario(auth.usuario.id_usuario).filter((c) => {
    const coincideTermino = !termino || c.numero_cotizacion.toLowerCase().includes(termino) || (c.observaciones || '').toLowerCase().includes(termino)
    const coincideEstado = !filtroEstado.value || c.estado === filtroEstado.value
    return coincideTermino && coincideEstado
  })
})

const cotizacionExpandida = ref(null)
function toggleDetalle(id) {
  cotizacionExpandida.value = cotizacionExpandida.value === id ? null : id
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
function fechaLarga(fecha) {
  return new Date(fecha).toLocaleDateString('es-CO', { day: 'numeric', month: 'long', year: 'numeric' })
}

// Cuenta regresiva de validez, visible siempre desde que se aprueba (no solo escondida en los
// últimos días). Devuelve también el % transcurrido de los `validez_dias` para la barra de progreso.
// El recordatorio por correo a los 5 días ya es real (backend/CotizacionService.java,
// enviarRecordatoriosVencimiento, job diario) — este cálculo de acá es solo lo que ve el cliente
// si entra al sitio, es independiente del correo.
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
// muestra en la UI — el cliente y el admin lo cuadran aparte por WhatsApp/privado. El registro del
// pago del anticipo en sí sigue siendo visible (ver el badge "Anticipo de servicio" en
// AdminVentasView.vue y el resumen de pago dentro de AdminCotizacionesView.vue).

// Cotizaciones aprobadas con productos van al Checkout real (carrito + pago).
// Cotizaciones aprobadas de solo servicios no pasan por el carrito: se confirman directo,
// ya que el pago/agenda de un servicio se coordina aparte con el cliente.
function procederAlPago(cot) {
  const items = itemsDe(cot.id_cotizacion)
  items.productos.forEach((p) => {
    const producto = catalog.getProductById(p.id_producto)
    if (producto) cart.agregarProducto(producto, p.cantidad)
  })
  if (items.servicios.length) {
    showToast('Los servicios de esta cotización se coordinarán directamente contigo — te contactaremos para agendar y cobrar aparte.', 'info')
  }
  router.push({ path: '/checkout', query: { fromCotizacion: cot.id_cotizacion } })
}

// Una cotización de solo servicio ya no se confirma con un simple "¿seguro?" del navegador —
// el cliente paga un anticipo (simulado) antes de que la cita quede agendada de verdad, para
// que el admin no bloquee una fecha en su calendario por algo que nadie tomó en serio.
const cotizacionAAnticipar = ref(null)
const metodoAnticipo = ref('tarjeta')
const procesandoAnticipo = ref(false)
const anticipoConfirmado = ref(null)

function abrirAnticipo(cot) {
  cotizacionAAnticipar.value = cot
  metodoAnticipo.value = 'tarjeta'
}
function cancelarAnticipo() {
  cotizacionAAnticipar.value = null
}
async function confirmarAnticipo() {
  const cot = cotizacionAAnticipar.value
  if (!cot) return
  procesandoAnticipo.value = true
  await new Promise((r) => setTimeout(r, 1400))
  procesandoAnticipo.value = false

  const anticipo = montoAnticipo(cot.total_estimado)
  try {
    await ventasStore.crearVenta({
      id_usuario: auth.usuario.id_usuario,
      items: [],
      subtotal: anticipo,
      total: anticipo,
      metodo_pago: metodoAnticipo.value,
      // Un anticipo de servicio no envía nada. El backend ya asume 'recogida' si el campo falta,
      // pero se manda explícito para dejar claro el "sin envío" y no depender del default.
      metodo_envio: 'recogida',
      id_cotizacion: cot.id_cotizacion,
      notas_cliente: `Anticipo (${Math.round(PORCENTAJE_ANTICIPO * 100)}%) de la cotización ${cot.numero_cotizacion}`,
    })
    await cotizStore.actualizarEstado(cot.id_cotizacion, 'convertida_venta')
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo confirmar el anticipo. Intenta de nuevo.', 'danger')
    return
  }

  const fechaDeseadaIso = extraerFechaDeseada(cot.observaciones)
  anticipoConfirmado.value = { numero: cot.numero_cotizacion, monto: anticipo, fecha: fechaDeseadaIso ? formatDate(fechaDeseadaIso) : null }
  cotizacionAAnticipar.value = null
  showToast('¡Anticipo confirmado! Te enviamos a tu correo el detalle del servicio y lo que queda pendiente.', 'success')
}

// El cliente también puede rechazar una cotización ya aprobada (ej. si cambió de planes o
// encontró algo mejor) en vez de solo dejarla vencer. Con advertencia grande antes de confirmar,
// porque es una decisión final: una vez rechazada, tocaría pedir una cotización nueva.
const cotizacionARechazar = ref(null)
const notaRechazoCliente = ref('')

function abrirRechazoCliente(cot) {
  cotizacionARechazar.value = cot
  notaRechazoCliente.value = ''
}
function cancelarRechazoCliente() {
  cotizacionARechazar.value = null
}
async function confirmarRechazoCliente() {
  const cot = cotizacionARechazar.value
  if (!cot) return
  try {
    await cotizStore.actualizarEstado(cot.id_cotizacion, 'rechazada', notaRechazoCliente.value.trim() || 'Rechazada por el cliente.')
    showToast(`Cotización ${cot.numero_cotizacion} rechazada.`, 'info')
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo rechazar la cotización. Intenta de nuevo.', 'danger')
  }
  cotizacionARechazar.value = null
}

// ── Nueva cotización ──────────────────────────────────────────
const tipoPicker = ref('productos') // 'productos' | 'servicios'
const busquedaPicker = ref('')

// Los ítems del borrador solo guardan ids/cantidades — se reconstruyen contra el catálogo actual
// en vez de guardar el producto/servicio completo, para no quedar con una copia vieja de sus datos.
const itemsProductosSeleccionados = ref(
  (borradorInicial?.productos || [])
    .map((p) => {
      const producto = catalog.getProductById(p.id_producto)
      return producto ? { producto, cantidad: p.cantidad, precio_unitario: p.precio_unitario, esMayorista: p.esMayorista } : null
    })
    .filter(Boolean)
)
const itemsServiciosSeleccionados = ref(
  (borradorInicial?.servicios || [])
    .map((s) => {
      const servicio = catalog.getServiceById(s.id_servicio)
      return servicio ? { servicio, cantidad: s.cantidad, precio_estimado: s.precio_estimado, modo: s.modo } : null
    })
    .filter(Boolean)
)
const observaciones = ref(borradorInicial?.observaciones || '')
const fechaDeseada = ref(borradorInicial?.fechaDeseada || '')
const aceptaTerminos = ref(borradorInicial?.aceptaTerminos || false)

// Ubicación de ESTA solicitud (dónde se necesita el servicio/producto) - precargada desde el
// perfil si existe, pero editable: puede ser distinta de la dirección registrada del cliente.
// Mismo patrón de selects en cascada que Registro/Perfil/PQRS/Contacto/Checkout.
const departamento = ref(borradorInicial?.departamento || auth.usuario?.departamento || '')
const ciudad = ref(borradorInicial?.ciudad || auth.usuario?.ciudad || '')
const municipiosDisponibles = computed(() => MUNICIPIOS_POR_DEPARTAMENTO[departamento.value] || [])
watch(departamento, () => {
  if (!municipiosDisponibles.value.includes(ciudad.value)) {
    ciudad.value = ''
  }
})
const enviando = ref(false)

const resultadosPicker = computed(() => {
  const termino = busquedaPicker.value.toLowerCase()
  if (tipoPicker.value === 'productos') {
    // Solo se cotizan los productos que la empresa fabrica ella misma (ver catalog.js);
    // pedir cotización de algo que solo revenden (una lija, un rodillo) no tiene sentido.
    return catalog.productosFabricacionPropia.filter((p) => p.nombre.toLowerCase().includes(termino)).slice(0, 24)
  }
  return catalog.serviciosCatalogo.filter((s) => s.nombre_servicio.toLowerCase().includes(termino)).slice(0, 20)
})

// Umbral desde el que aplica el precio mayorista — el mismo que ya usaban en el detalle de producto.
const CANTIDAD_MINIMA_MAYORISTA = 10

function agregarProducto(producto, mayorista = false) {
  const existente = itemsProductosSeleccionados.value.find((i) => i.producto.id_producto === producto.id_producto)
  if (existente) {
    existente.cantidad++
  } else {
    const precioMayorista = mayorista && producto.precio_mayorista
    itemsProductosSeleccionados.value.push({
      producto,
      // Al pedir cotización mayorista arranca en la cantidad mínima habitual, para que el precio
      // que ve el cliente tenga sentido de una — puede subirla o bajarla libremente.
      cantidad: precioMayorista ? CANTIDAD_MINIMA_MAYORISTA : 1,
      precio_unitario: precioMayorista ? producto.precio_mayorista : producto.precio_venta,
      esMayorista: !!precioMayorista,
    })
  }
  showToast(`"${producto.nombre}" agregado a tu solicitud`, 'success')
}

// Por debajo del mínimo mayorista, el precio unitario vuelve al normal — el precio mayorista
// solo se aplica de verdad cuando la cantidad lo justifica.
function actualizarCantidadMayorista(item, nuevaCantidad) {
  item.cantidad = Math.max(1, nuevaCantidad)
  item.precio_unitario = item.cantidad >= CANTIDAD_MINIMA_MAYORISTA ? item.producto.precio_mayorista : item.producto.precio_venta
}

function agregarServicio(servicio) {
  const existente = itemsServiciosSeleccionados.value.find((i) => i.servicio.id_servicio === servicio.id_servicio)
  if (existente) {
    showToast('Ese servicio ya está en tu solicitud.', 'info')
    return
  }
  itemsServiciosSeleccionados.value.push({
    servicio,
    cantidad: 1,
    precio_estimado: tarifaServicio(servicio),
    // El propio incluye_materiales del servicio es solo la preselección: el cliente puede cambiarla.
    modo: servicio.incluye_materiales ? 'todo_incluido' : 'solo_servicio',
  })
  showToast(`"${servicio.nombre_servicio}" agregado a tu solicitud`, 'success')
}

function actualizarCantidadServicio(item, nuevaCantidad) {
  item.cantidad = Math.max(1, nuevaCantidad)
  if (unidadServicio(item.servicio) !== 'proyecto') {
    item.precio_estimado = tarifaServicio(item.servicio) * item.cantidad
  }
}

// Regla real del negocio (confirmada 2026-08-27, 3 niveles - reemplaza una versión anterior
// basada en zona_cobertura por servicio, dato que era mock, no real): Tesalia (donde está el
// local) es gratis; Paicol (al lado) solo tiene costo de desplazamiento a coordinar; cualquier
// otro lugar (incluidos Pitalito/La Plata/Neiva, antes considerados "zona cubierta") queda fuera
// de nuestro rango habitual - ahí se avisan las dos cosas juntas: que se evaluará si se puede
// atender, y que además tiene costo de desplazamiento. El valor del desplazamiento nunca lo
// calcula el sistema, se coordina directamente con el cliente. Aplica igual a cualquier servicio
// (no por servicio individual), y solo si hay al menos un servicio en el carrito - los productos
// van por transportadora, sin este costo.
const hayServicios = computed(() => itemsServiciosSeleccionados.value.length > 0)
const CIUDADES_SOLO_COSTO = ['paicol']
const nivelZona = computed(() => {
  if (!hayServicios.value || !ciudad.value) return null
  const c = ciudad.value.trim().toLowerCase()
  if (c === 'tesalia') return 'gratis'
  if (CIUDADES_SOLO_COSTO.includes(c)) return 'costo'
  return 'evaluar'
})
const tieneCostoDesplazamiento = computed(() => nivelZona.value === 'costo' || nivelZona.value === 'evaluar')
const fueraDeRangoHabitual = computed(() => nivelZona.value === 'evaluar')

function quitarProducto(idx) {
  itemsProductosSeleccionados.value.splice(idx, 1)
}
function quitarServicio(idx) {
  itemsServiciosSeleccionados.value.splice(idx, 1)
}

function guardarBorrador() {
  localStorage.setItem(
    claveBorrador(),
    JSON.stringify({
      tabActiva: tabActiva.value,
      observaciones: observaciones.value,
      fechaDeseada: fechaDeseada.value,
      aceptaTerminos: aceptaTerminos.value,
      departamento: departamento.value,
      ciudad: ciudad.value,
      productos: itemsProductosSeleccionados.value.map((i) => ({
        id_producto: i.producto.id_producto, cantidad: i.cantidad, precio_unitario: i.precio_unitario, esMayorista: i.esMayorista,
      })),
      servicios: itemsServiciosSeleccionados.value.map((i) => ({
        id_servicio: i.servicio.id_servicio, cantidad: i.cantidad, precio_estimado: i.precio_estimado, modo: i.modo,
      })),
    })
  )
}
watch(
  [tabActiva, itemsProductosSeleccionados, itemsServiciosSeleccionados, observaciones, fechaDeseada, aceptaTerminos, departamento, ciudad],
  guardarBorrador,
  { deep: true }
)

// Cuando se llega desde "Cotizar" en un producto/servicio (Detalle, Servicios, Carrito), la
// solicitud se abre directo en "Nueva cotización" con ese ítem ya agregado. Se usa `watch` en vez
// de `onMounted` porque esta vista queda con <keep-alive> (para no perder el borrador si el
// cliente sale a leer los Términos y vuelve) — Vue no vuelve a montarla ni a repetir onMounted
// si ya estaba viva y solo cambia la query (ej. pedir "Cotizar" en un segundo servicio).
watch(
  () => route.query,
  (query) => {
    if (query.nuevoServicio) {
      const servicio = catalog.getServiceById(query.nuevoServicio)
      if (servicio) {
        tabActiva.value = 'nueva'
        agregarServicio(servicio)
        const itemAgregado = itemsServiciosSeleccionados.value.find((i) => i.servicio.id_servicio === servicio.id_servicio)
        if (itemAgregado) {
          if (query.horas) actualizarCantidadServicio(itemAgregado, Number(query.horas))
          if (query.modo === 'todo_incluido' || query.modo === 'solo_servicio') itemAgregado.modo = query.modo
        }
      }
    } else if (query.nuevoProducto) {
      const producto = catalog.getProductById(query.nuevoProducto)
      if (producto) {
        tabActiva.value = 'nueva'
        agregarProducto(producto, query.mayorista === '1')
      }
    } else if (query.verCotizacion) {
      // Desde el aviso de "tu cotización fue aprobada/rechazada" - lleva directo al historial con
      // esa cotización ya abierta, en vez de la lista general que el cliente tendría que buscar.
      tabActiva.value = 'mis-cotizaciones'
      cotizacionExpandida.value = Number(query.verCotizacion)
      if (auth.usuario) cotizStore.marcarCotizacionesVistas(auth.usuario.id_usuario)
    } else {
      return
    }
    router.replace({ path: '/cotizaciones' })
  },
  { immediate: true }
)

// Servicios sugeridos según la categoría de los productos elegidos (algoritmo compartido en catalog.js,
// reutilizado también en Detalle de producto y Carrito).
const serviciosSugeridos = computed(() => {
  if (!itemsProductosSeleccionados.value.length) return []
  const categorias = itemsProductosSeleccionados.value.map((item) => item.producto.id_categoria)
  const idsYaSeleccionados = itemsServiciosSeleccionados.value.map((i) => i.servicio.id_servicio)
  return catalog.getServiciosSugeridos(categorias, idsYaSeleccionados).slice(0, 4)
})

const totalSolicitud = computed(() => {
  const totalProd = itemsProductosSeleccionados.value.reduce((sum, i) => sum + i.cantidad * i.precio_unitario, 0)
  const totalServ = itemsServiciosSeleccionados.value.reduce((sum, i) => sum + i.precio_estimado, 0)
  return totalProd + totalServ
})

// Observaciones es obligatorio (y con un mínimo de caracteres, no basta con poner cualquier cosa)
// para que el equipo sepa de verdad qué necesita el cliente antes de cotizar — sin esto, una
// cotización de "Estuco Plástico x10" a secas no dice nada sobre el proyecto real.
const MIN_OBSERVACIONES = 15

const hayItems = computed(() => itemsProductosSeleccionados.value.length > 0 || itemsServiciosSeleccionados.value.length > 0)
const requiereFecha = computed(() => hayServicios.value)
const observacionesValidas = computed(() => observaciones.value.trim().length >= MIN_OBSERVACIONES)
const puedeEnviar = computed(() => hayItems.value && aceptaTerminos.value && observacionesValidas.value && !!departamento.value && !!ciudad.value && (!requiereFecha.value || fechaDeseada.value))

async function enviarSolicitud() {
  if (!hayItems.value) {
    showToast('Agrega al menos un producto o servicio a tu solicitud.', 'danger')
    return
  }
  if (!departamento.value || !ciudad.value) {
    showToast('Indica el departamento y la ciudad donde necesitas esto.', 'danger')
    return
  }
  if (!observacionesValidas.value) {
    showToast(`Cuéntanos qué necesitas (mínimo ${MIN_OBSERVACIONES} caracteres) para poder cotizarte bien.`, 'danger')
    return
  }
  if (requiereFecha.value && !fechaDeseada.value) {
    showToast('Cuéntanos cuándo necesitas el servicio.', 'danger')
    return
  }
  if (!aceptaTerminos.value) {
    showToast('Debes aceptar los Términos y Condiciones para continuar.', 'danger')
    return
  }
  enviando.value = true

  const observacionesFinal = fechaDeseada.value
    ? empacarFechaDeseada(fechaDeseada.value, observaciones.value.trim() || null)
    : observaciones.value

  try {
    await cotizStore.crearCotizacion({
      itemsProductos: itemsProductosSeleccionados.value,
      itemsServicios: itemsServiciosSeleccionados.value,
      observaciones: observacionesFinal,
      departamento: departamento.value,
      ciudad: ciudad.value,
    })
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo enviar la cotización. Intenta de nuevo.', 'danger')
    enviando.value = false
    return
  }

  itemsProductosSeleccionados.value = []
  itemsServiciosSeleccionados.value = []
  observaciones.value = ''
  fechaDeseada.value = ''
  aceptaTerminos.value = false
  enviando.value = false
  showToast('¡Cotización enviada! La revisamos y te avisamos por correo con el precio y la fecha (máximo 24 horas).', 'success')
  tabActiva.value = 'mis-cotizaciones'
}
</script>

<template>
  <section class="services-hero">
    <div class="services-hero-bg">
      <img src="https://images.unsplash.com/photo-1589939705384-5185137a7f0f?w=1600&q=80" alt="Cotizaciones Acabados 1A" />
    </div>
    <div class="container">
      <div class="breadcrumb">
        <span class="breadcrumb-item"><RouterLink to="/"><i class="ri-home-4-line"></i> Inicio</RouterLink></span>
        <span class="breadcrumb-sep"><i class="ri-arrow-right-s-line"></i></span>
        <span class="breadcrumb-item active">Cotizaciones</span>
      </div>
      <h1 class="services-hero-title">Solicita tu <em>Cotización</em></h1>
      <p class="services-hero-desc">
        Cuéntanos qué necesitas —un producto, un servicio o ambos— y nuestro equipo te responde con una propuesta clara y a tu medida.
      </p>
    </div>
  </section>

  <div class="container">
    <div class="services-stats-card">
      <div class="services-stat"><i class="ri-price-tag-3-line"></i><strong>100%</strong><span>Gratis, sin costo</span></div>
      <div class="services-stat"><i class="ri-time-line"></i><strong>24h</strong><span>Tiempo de respuesta</span></div>
      <div class="services-stat"><i class="ri-shield-check-line"></i><strong>0%</strong><span>Sin compromiso</span></div>
    </div>
  </div>

  <section class="section">
    <div class="container">
      <template v-if="!auth.isAuthenticated">
        <div class="cotiz-landing">
          <div class="cotiz-landing-icon"><i class="ri-file-list-3-line"></i></div>
          <h2 class="cotiz-landing-title">Inicia sesión para continuar</h2>
          <p class="cotiz-landing-desc">
            Crea una cuenta gratis o inicia sesión para solicitar tu cotización y hacerle seguimiento desde tu perfil.
          </p>

          <div class="cotiz-landing-actions">
            <button class="btn btn-primary btn-lg" @click="mostrarModalLogin = true">
              <i class="ri-login-box-line"></i> Iniciar Sesión para Cotizar
            </button>
            <RouterLink to="/registro" class="cotiz-landing-link">¿No tienes cuenta? Regístrate gratis</RouterLink>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="cotiz-tabs">
          <button class="cotiz-tab" :class="{ active: tabActiva === 'mis-cotizaciones' }" @click="tabActiva = 'mis-cotizaciones'">
            <i class="ri-file-list-3-line"></i> Mis Cotizaciones <span class="badge-count">{{ misCotizaciones.length }}</span>
          </button>
          <button class="cotiz-tab" :class="{ active: tabActiva === 'nueva' }" @click="tabActiva = 'nueva'">
            <i class="ri-add-line"></i> Nueva Cotización
          </button>
        </div>

        <!-- MIS COTIZACIONES -->
        <div v-if="tabActiva === 'mis-cotizaciones'">
          <div v-if="!cotizStore.getCotizacionesDeUsuario(auth.usuario.id_usuario).length" class="cotiz-empty">
            <i class="ri-inbox-line"></i>
            <h2 class="section-title">Aquí aparecerán tus cotizaciones</h2>
            <p class="section-subtitle mb-24">
              Todavía no has solicitado ninguna. Cuando pidas una cotización de productos o servicios,
              la verás en esta sección con su estado y todos los detalles, para que le hagas seguimiento cuando quieras.
            </p>
            <button class="btn btn-primary" @click="tabActiva = 'nueva'"><i class="ri-add-line"></i> Solicitar mi primera cotización</button>
          </div>

          <div v-else>
            <div class="services-toolbar">
              <div class="search-input-wrap">
                <i class="ri-search-line search-input-icon"></i>
                <input v-model="busquedaCotizaciones" type="search" class="form-control" placeholder="Buscar por número u observación..." style="padding-left:38px;" />
              </div>
              <select v-model="filtroEstado">
                <option value="">Todos los estados</option>
                <option v-for="(v, k) in cotizStore.ESTADOS" :key="k" :value="k">{{ v.label }}</option>
              </select>
            </div>

            <p v-if="!misCotizaciones.length" class="text-muted text-center" style="padding:40px 0;">No hay cotizaciones que coincidan con la búsqueda.</p>
            <div v-for="cot in misCotizaciones" :key="cot.id_cotizacion" class="cotiz-card">
              <div class="cotiz-card-header">
                <div>
                  <div class="cotiz-card-numero">{{ cot.numero_cotizacion }}</div>
                  <div class="cotiz-card-fecha">{{ fechaLarga(cot.fecha) }}</div>
                </div>
                <span v-if="cotizStore.estaVencida(cot)" class="badge badge-gray">
                  <i class="ri-time-line"></i> Vencida
                </span>
                <span v-else class="badge" :class="cotizStore.ESTADOS[cot.estado].badge">
                  <i :class="cotizStore.ESTADOS[cot.estado].icon"></i> {{ cotizStore.ESTADOS[cot.estado].label }}
                </span>
              </div>

              <div class="cotiz-card-meta">
                <div class="cotiz-card-meta-item destacado"><span>Total estimado</span><strong>{{ formatCOP(cot.total_estimado) }}</strong></div>
                <div class="cotiz-card-meta-item destacado"><span>Validez</span><strong>{{ cot.validez_dias }} días</strong></div>
              </div>

              <div v-if="cotizStore.estaVencida(cot)" class="cotiz-estado-banner convertida">
                <i class="ri-time-line"></i>
                <div>
                  <p>Esta cotización aprobada ya venció sin confirmarse. Solicita una nueva y con gusto te la preparamos otra vez.</p>
                </div>
              </div>
              <div v-else class="cotiz-estado-banner" :class="cotizStore.ESTADOS[cot.estado].banner">
                <i :class="cotizStore.ESTADOS[cot.estado].icon"></i>
                <div>
                  <p>{{ cotizStore.ESTADOS[cot.estado].mensaje }}</p>
                  <p v-if="cot.estado === 'rechazada' && cot.respuesta" class="cotiz-estado-motivo"><strong>Motivo:</strong> {{ cot.respuesta }}</p>
                  <p v-else-if="cot.estado === 'aprobada' && cot.respuesta" class="cotiz-estado-motivo">{{ cot.respuesta }}</p>
                </div>
              </div>

              <div v-if="diasRestantesInfo(cot)" class="cotiz-validez-progreso" :class="diasRestantesInfo(cot).urgencia">
                <div class="cotiz-validez-progreso-top">
                  <span><i class="ri-alarm-warning-line"></i> Validez de tu cotización</span>
                  <strong>{{ diasRestantesInfo(cot).texto }}</strong>
                </div>
                <div class="cotiz-validez-progreso-bar"><div class="cotiz-validez-progreso-fill" :style="{ width: diasRestantesInfo(cot).porcentaje + '%' }"></div></div>
                <span class="cotiz-validez-progreso-sub">Tienes hasta ese día para pagar y confirmar antes de que venza.</span>
              </div>

              <div v-if="extraerFechaDeseada(cot.observaciones)" class="cotiz-fecha-deseada-chip">
                <i class="ri-calendar-event-line"></i> Fecha deseada del servicio: <strong>{{ formatDate(extraerFechaDeseada(cot.observaciones)) }}</strong>
              </div>
              <p v-if="extraerNotaCliente(cot.observaciones)" class="cotiz-card-obs"><strong>Tu nota:</strong> {{ extraerNotaCliente(cot.observaciones) }}</p>

              <template v-if="cotizacionExpandida === cot.id_cotizacion">
                <div style="margin: 4px 0 14px;">
                  <div v-for="p in itemsDe(cot.id_cotizacion).productos" :key="'p' + p.id_detalle" class="cotiz-detail-item">
                    <img :src="catalog.getProductById(p.id_producto)?.imagen_url" :alt="nombreProducto(p.id_producto)" />
                    <span>{{ nombreProducto(p.id_producto) }}<span class="cotiz-detail-item-meta">x{{ p.cantidad }}</span></span>
                    <span>{{ formatCOP(p.subtotal) }}</span>
                  </div>
                  <div v-for="s in itemsDe(cot.id_cotizacion).servicios" :key="'s' + s.id_detalle" class="cotiz-detail-item">
                    <img :src="catalog.getServiceById(s.id_servicio)?.imagen_url" :alt="nombreServicio(s.id_servicio)" />
                    <span>{{ nombreServicio(s.id_servicio) }}</span>
                    <span>{{ formatCOP(s.subtotal) }}</span>
                  </div>
                  <div class="cotiz-detail-total"><span>Total</span><span>{{ formatCOP(cot.total_estimado) }}</span></div>
                </div>
              </template>

              <div class="cotiz-card-footer">
                <template v-if="cot.estado === 'aprobada' && !cotizStore.estaVencida(cot)">
                  <button v-if="itemsDe(cot.id_cotizacion).productos.length" class="btn btn-primary btn-sm" @click="procederAlPago(cot)">
                    <i class="ri-bank-card-line"></i> Aceptar y Pagar
                  </button>
                  <button v-else class="btn btn-primary btn-sm" @click="abrirAnticipo(cot)">
                    <i class="ri-calendar-check-line"></i> Aceptar Cotización
                  </button>
                  <button class="btn btn-sm" style="background:rgba(192,57,43,0.08);color:var(--primary);" @click="abrirRechazoCliente(cot)">
                    <i class="ri-close-circle-line"></i> Rechazar cotización
                  </button>
                </template>
                <button v-else-if="cotizStore.estaVencida(cot)" class="btn btn-outline-red btn-sm" @click="tabActiva = 'nueva'">
                  <i class="ri-refresh-line"></i> Solicitar de nuevo
                </button>
                <button class="btn btn-outline-red btn-sm" @click="toggleDetalle(cot.id_cotizacion)">
                  {{ cotizacionExpandida === cot.id_cotizacion ? 'Ocultar detalle' : 'Ver detalle' }}
                  <i :class="cotizacionExpandida === cot.id_cotizacion ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'"></i>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- NUEVA COTIZACIÓN -->
        <div v-else class="cotiz-nueva-layout">
          <div>
          <div class="admin-card">
            <div class="admin-card-body">
              <div class="filter-chips mb-16">
                <button class="filter-chip" :class="{ active: tipoPicker === 'productos' }" @click="tipoPicker = 'productos'">
                  <i class="ri-shopping-bag-line"></i> Productos
                </button>
                <button class="filter-chip" :class="{ active: tipoPicker === 'servicios' }" @click="tipoPicker = 'servicios'">
                  <i class="ri-tools-line"></i> Servicios
                </button>
              </div>

              <div class="search-input-wrap">
                <i class="ri-search-line search-input-icon"></i>
                <input
                  v-model="busquedaPicker"
                  type="search"
                  class="form-control"
                  :placeholder="tipoPicker === 'productos' ? 'Buscar producto...' : 'Buscar servicio...'"
                  style="padding-left:38px;"
                />
              </div>

              <!-- Productos: solo los que la empresa fabrica ella misma, en tarjetas de 3 por fila -->
              <div v-if="tipoPicker === 'productos'" class="cotiz-picker-grid">
                <div v-for="p in resultadosPicker" :key="p.id_producto" class="cotiz-picker-card">
                  <img :src="p.imagen_url" :alt="p.nombre" />
                  <div class="cotiz-picker-card-body">
                    <strong>{{ p.nombre }}</strong>
                    <p class="cotiz-picker-card-desc">{{ p.descripcion }}</p>
                    <span class="cotiz-picker-card-precio">{{ formatCOP(p.precio_venta) }}</span>
                  </div>
                  <button class="btn btn-primary btn-sm btn-block" @click="agregarProducto(p, true)"><i class="ri-add-line"></i> Agregar</button>
                </div>
                <p v-if="!resultadosPicker.length" class="text-muted text-center" style="grid-column:1/-1;padding:20px 0;">No se encontraron resultados.</p>
              </div>

              <!-- Servicios: tarjetas de 3 por fila, con descripción y precio destacado -->
              <div v-else class="cotiz-picker-grid">
                <div v-for="s in resultadosPicker" :key="s.id_servicio" class="cotiz-picker-card">
                  <img :src="s.imagen_url" :alt="s.nombre_servicio" />
                  <div class="cotiz-picker-card-body">
                    <strong>{{ s.nombre_servicio }}</strong>
                    <p class="cotiz-picker-card-desc">{{ s.descripcion }}</p>
                    <span class="cotiz-picker-card-precio">
                      <template v-if="s.precio_hora">{{ formatCOP(s.precio_hora) }}<small>/hora</small></template>
                      <template v-else-if="s.precio_dia">{{ formatCOP(s.precio_dia) }}<small>/día</small></template>
                      <template v-else>Desde {{ formatCOP(s.precio_proyecto) }}</template>
                    </span>
                  </div>
                  <button class="btn btn-primary btn-sm btn-block" @click="agregarServicio(s)"><i class="ri-add-line"></i> Agregar</button>
                </div>
                <p v-if="!resultadosPicker.length" class="text-muted text-center" style="grid-column:1/-1;padding:20px 0;">No se encontraron resultados.</p>
              </div>
            </div>
          </div>

          <div v-if="serviciosSugeridos.length" class="cotiz-sugeridos-card">
            <div class="cotiz-sugeridos-title"><i class="ri-magic-line"></i> Servicios que podrías incluir</div>
            <p class="cotiz-sugeridos-sub">Según los productos que elegiste, estos servicios podrían completar tu proyecto.</p>
            <div class="cotiz-sugeridos-grid">
              <div v-for="s in serviciosSugeridos" :key="s.id_servicio" class="cotiz-sugerido-item">
                <img :src="s.imagen_url" :alt="s.nombre_servicio" />
                <div class="cotiz-sugerido-info">
                  <strong>{{ s.nombre_servicio }}</strong>
                  <span>{{ s.precio_hora ? `${formatCOP(s.precio_hora)}/hora` : s.precio_dia ? `${formatCOP(s.precio_dia)}/día` : `Desde ${formatCOP(s.precio_proyecto)}` }}</span>
                </div>
                <button class="btn btn-outline-red btn-sm" @click="agregarServicio(s)"><i class="ri-add-line"></i> Agregar</button>
              </div>
            </div>
          </div>
          </div>

          <aside class="order-summary">
            <h3 class="font-main fw-800 mb-16">Tu solicitud</h3>

            <p v-if="!itemsProductosSeleccionados.length && !itemsServiciosSeleccionados.length" class="text-muted" style="font-size:0.85rem;">
              Agrega productos o servicios desde la izquierda para armar tu cotización.
            </p>
            <template v-else>
              <!-- Producto normal -->
              <template v-for="(item, idx) in itemsProductosSeleccionados" :key="'sp' + item.producto.id_producto">
              <div
                v-if="!item.esMayorista"
                class="cotiz-selected-item"
              >
                <div class="cotiz-selected-item-name">
                  <span class="cotiz-item-kicker cotiz-item-kicker-producto"><i class="ri-shopping-bag-line"></i> Producto</span>
                  {{ item.producto.nombre }}
                  <small>{{ formatCOP(item.precio_unitario) }} c/u</small>
                </div>
                <div class="qty-control">
                  <button class="qty-btn" @click="item.cantidad = Math.max(1, item.cantidad - 1)">-</button>
                  <input class="qty-input" type="number" min="1" :value="item.cantidad" @change="item.cantidad = Math.max(1, Number($event.target.value))" />
                  <button class="qty-btn" @click="item.cantidad++">+</button>
                </div>
                <i class="ri-delete-bin-line cotiz-selected-remove" @click="quitarProducto(idx)"></i>
              </div>

              <!-- Producto al por mayor: apartado destacado con comparación de precios y matemática clara -->
              <div
                v-else
                class="cotiz-selected-mayorista"
              >
                <div class="cotiz-selected-item" style="padding:0 0 10px;border-bottom:none;">
                  <div class="cotiz-selected-item-name">
                    <span class="cotiz-item-kicker cotiz-item-kicker-producto"><i class="ri-shopping-bag-line"></i> Producto</span>
                    {{ item.producto.nombre }}
                    <span class="badge badge-green" style="font-size:0.62rem;margin-left:6px;">Mayorista</span>
                  </div>
                  <i class="ri-delete-bin-line cotiz-selected-remove" @click="quitarProducto(idx)"></i>
                </div>

                <div class="cotiz-precio-comparativa">
                  <div>
                    <span>Precio normal</span>
                    <strong>{{ formatCOP(item.producto.precio_venta) }}</strong>
                  </div>
                  <div>
                    <span>Precio mayorista</span>
                    <strong style="color:var(--success);">{{ formatCOP(item.producto.precio_mayorista) }}</strong>
                  </div>
                </div>
                <p class="cotiz-precio-comparativa-nota">El precio mayorista aplica desde {{ CANTIDAD_MINIMA_MAYORISTA }} unidades.</p>

                <div class="cotiz-servicio-stepper-row">
                  <span>Cantidad</span>
                  <div class="qty-control">
                    <button class="qty-btn" @click="actualizarCantidadMayorista(item, item.cantidad - 1)">-</button>
                    <input class="qty-input" type="number" min="1" :value="item.cantidad" @change="actualizarCantidadMayorista(item, Number($event.target.value))" />
                    <button class="qty-btn" @click="actualizarCantidadMayorista(item, item.cantidad + 1)">+</button>
                  </div>
                </div>

                <div class="cotiz-servicio-total">
                  <span>{{ item.cantidad }} &times; {{ formatCOP(item.precio_unitario) }} ({{ item.cantidad >= CANTIDAD_MINIMA_MAYORISTA ? 'mayorista' : 'normal' }})</span>
                  <strong>{{ formatCOP(item.cantidad * item.precio_unitario) }}</strong>
                </div>

                <p v-if="item.cantidad < CANTIDAD_MINIMA_MAYORISTA" class="cotiz-modo-warning">
                  <i class="ri-error-warning-line"></i> Con menos de {{ CANTIDAD_MINIMA_MAYORISTA }} unidades no aplica el precio mayorista — el precio mostrado ya volvió al normal. Sube la cantidad a {{ CANTIDAD_MINIMA_MAYORISTA }} o más para acceder al precio mayorista.
                </p>

                <p class="cotiz-zona-warning">
                  <i class="ri-price-tag-3-line"></i> Precio mayorista sujeto a confirmación de nuestro equipo según disponibilidad e inventario.
                </p>
              </div>
              </template>

              <div v-for="(item, idx) in itemsServiciosSeleccionados" :key="'ss' + item.servicio.id_servicio" class="cotiz-selected-service">
                <div class="cotiz-selected-item">
                  <div class="cotiz-selected-item-name">
                    <span class="cotiz-item-kicker cotiz-item-kicker-servicio"><i class="ri-tools-line"></i> Servicio</span>
                    {{ item.servicio.nombre_servicio }}
                    <small v-if="unidadServicio(item.servicio) !== 'proyecto'">
                      {{ formatCOP(tarifaServicio(item.servicio)) }} /{{ unidadServicio(item.servicio) === 'hora' ? 'hora' : 'día' }}
                    </small>
                  </div>
                  <i class="ri-delete-bin-line cotiz-selected-remove" @click="quitarServicio(idx)"></i>
                </div>

                <div v-if="unidadServicio(item.servicio) !== 'proyecto'" class="cotiz-servicio-stepper-row">
                  <span>{{ unidadServicio(item.servicio) === 'hora' ? 'Horas estimadas' : 'Días estimados' }}</span>
                  <div class="qty-control">
                    <button class="qty-btn" type="button" @click="actualizarCantidadServicio(item, item.cantidad - 1)">-</button>
                    <input class="qty-input" type="number" min="1" :value="item.cantidad" @change="actualizarCantidadServicio(item, Number($event.target.value))" />
                    <button class="qty-btn" type="button" @click="actualizarCantidadServicio(item, item.cantidad + 1)">+</button>
                  </div>
                </div>

                <div class="cotiz-servicio-total">
                  <span>Total de este servicio</span>
                  <strong>{{ formatCOP(item.precio_estimado) }}</strong>
                </div>

                <!-- Modalidad materiales/solo mano de obra: solo aplica a servicios donde el
                     negocio pone los materiales (incluye_materiales). En asesorías, consultorías
                     y mantenimientos no hay materiales que decidir — mismo criterio que
                     DetalleServicioView. -->
                <template v-if="item.servicio.incluye_materiales">
                  <div class="cotiz-modo-toggle">
                    <button type="button" class="cotiz-modo-chip" :class="{ active: item.modo === 'todo_incluido' }" @click="item.modo = 'todo_incluido'">
                      <i class="ri-checkbox-multiple-line"></i> Todo incluido
                    </button>
                    <button type="button" class="cotiz-modo-chip" :class="{ active: item.modo === 'solo_servicio' }" @click="item.modo = 'solo_servicio'">
                      <i class="ri-hammer-line"></i> Solo servicio
                    </button>
                  </div>
                  <p class="cotiz-modo-warning"><i class="ri-information-line"></i> {{ ADVERTENCIA_MODO[item.modo] }}</p>
                </template>
              </div>

              <div class="order-summary-row total" style="margin-top:14px;">
                <span>Total estimado</span>
                <span class="value">{{ formatCOP(totalSolicitud) }}</span>
              </div>
            </template>

            <div class="auth-form-row" style="margin-top:18px;">
              <div class="form-group">
                <label class="form-label required">Departamento</label>
                <select v-model="departamento" class="form-control" required>
                  <option value="" disabled>Seleccionar...</option>
                  <option v-for="depto in DEPARTAMENTOS" :key="depto" :value="depto">{{ depto }}</option>
                </select>
              </div>
              <div class="form-group">
                <label class="form-label required">Ciudad / Municipio</label>
                <select v-model="ciudad" class="form-control" required :disabled="!departamento">
                  <option value="" disabled>{{ departamento ? 'Seleccionar...' : 'Elige primero un departamento' }}</option>
                  <option v-for="mun in municipiosDisponibles" :key="mun" :value="mun">{{ mun }}</option>
                </select>
              </div>
            </div>
            <p class="cotiz-obs-hint">¿Dónde necesitas esto? Puede ser distinto a tu dirección registrada.</p>

            <div v-if="fueraDeRangoHabitual" class="cotiz-fecha-card cotiz-zona-alerta" style="margin-top:14px;">
              <div class="cotiz-fecha-card-icon"><i class="ri-map-pin-range-line"></i></div>
              <div class="cotiz-fecha-card-body">
                <label>Fuera de nuestro rango habitual de trabajo</label>
                <p style="margin:0;font-size:0.85rem;line-height:1.5;">
                  Trabajamos habitualmente en Tesalia y Paicol. Para {{ ciudad }} vamos a evaluar la distancia antes
                  de confirmar si podemos atenderte.
                </p>
              </div>
            </div>

            <div v-if="tieneCostoDesplazamiento" class="cotiz-fecha-card cotiz-zona-alerta" style="margin-top:14px;">
              <div class="cotiz-fecha-card-icon"><i class="ri-route-line"></i></div>
              <div class="cotiz-fecha-card-body">
                <label>Este servicio tiene costo de desplazamiento</label>
                <p style="margin:0;font-size:0.85rem;line-height:1.5;">
                  Nuestro local está en Tesalia — para {{ ciudad }} el servicio incluye un costo adicional de
                  desplazamiento. El valor exacto no queda en este estimado: lo coordinamos directamente contigo
                  cuando te contactemos.
                </p>
              </div>
            </div>

            <div v-if="requiereFecha" class="cotiz-fecha-card" style="margin-top:18px;">
              <div class="cotiz-fecha-card-icon"><i class="ri-calendar-event-line"></i></div>
              <div class="cotiz-fecha-card-body">
                <label for="fecha-deseada-servicio">¿Cuándo necesitas el servicio?</label>
                <input id="fecha-deseada-servicio" v-model="fechaDeseada" type="date" class="form-control" :min="fechaLocalISO()" />
              </div>
            </div>

            <div class="form-group" style="margin-top:18px;">
              <label class="form-label required">Cuéntanos qué necesitas</label>
              <p class="cotiz-obs-hint">Entre más detalles nos des, más preciso será tu presupuesto. Por ejemplo: ¿qué espacio es?, ¿aproximadamente cuántos m²?, ¿algún color o acabado en mente?</p>
              <textarea
                v-model="observaciones"
                class="form-control"
                rows="3"
                placeholder="Ej: Necesito pintar la sala y dos habitaciones de un apartamento, unos 60m² en total, prefiero tonos claros..."
              ></textarea>
              <span class="cotiz-obs-counter" :class="{ ok: observacionesValidas }">
                {{ observaciones.trim().length }}/{{ MIN_OBSERVACIONES }} caracteres mínimo
              </span>
            </div>

            <label class="cotiz-terminos-check">
              <input v-model="aceptaTerminos" type="checkbox" />
              <span>
                He leído y acepto los
                <RouterLink to="/terminos-y-condiciones?from=cotizaciones">Términos y Condiciones</RouterLink>
                de Acabados y Diseños 1A.
              </span>
            </label>

            <button class="btn btn-primary btn-lg btn-block" :disabled="!puedeEnviar || enviando" @click="enviarSolicitud">
              {{ enviando ? 'Enviando...' : 'Enviar Solicitud' }} <i class="ri-send-plane-line"></i>
            </button>
          </aside>
        </div>
      </template>
    </div>
  </section>

  <CotizarLoginModal :mostrar="mostrarModalLogin" @cerrar="mostrarModalLogin = false" @iniciar-sesion="irALogin" />

  <!-- Confirmación de rechazo (cliente): advertencia grande antes de una decisión que no se puede deshacer -->
  <Transition name="confirm-modal-fade">
    <div v-if="cotizacionARechazar" class="confirm-modal-overlay" @click.self="cancelarRechazoCliente">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="cancelarRechazoCliente"><i class="ri-close-line"></i></button>
          <div class="confirm-modal-icon"><i class="ri-alert-line"></i></div>
          <h3 class="confirm-modal-title">¿Rechazar esta cotización?</h3>
          <p class="confirm-modal-text">
            La cotización <strong>{{ cotizacionARechazar.numero_cotizacion }}</strong> por {{ formatCOP(cotizacionARechazar.total_estimado) }}
            quedará rechazada. Esta acción no se puede deshacer — si cambias de opinión, tendrías que solicitar una cotización nueva.
          </p>
          <div class="form-group" style="text-align:left;margin-bottom:20px;">
            <label class="form-label">¿Por qué la rechazas? (opcional)</label>
            <textarea
              v-model="notaRechazoCliente"
              class="form-control"
              rows="2"
              placeholder="Ej: encontré un precio mejor, cambié de planes, ya no lo necesito..."
            ></textarea>
          </div>
          <div class="confirm-modal-actions">
            <button class="btn btn-sm" style="background:var(--danger);color:white;" @click="confirmarRechazoCliente">
              <i class="ri-close-circle-line"></i> Sí, rechazar cotización
            </button>
            <button class="btn btn-outline-red btn-block" @click="cancelarRechazoCliente">Cancelar</button>
          </div>
        </div>
      </Transition>
    </div>
  </Transition>

  <!-- Anticipo para confirmar una cotización de solo servicio -->
  <Transition name="confirm-modal-fade">
    <div v-if="cotizacionAAnticipar" class="confirm-modal-overlay" @click.self="!procesandoAnticipo && cancelarAnticipo()">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal">
          <button v-if="!procesandoAnticipo" class="confirm-modal-close" aria-label="Cerrar" @click="cancelarAnticipo"><i class="ri-close-line"></i></button>

          <template v-if="!procesandoAnticipo">
            <div class="confirm-modal-icon"><i class="ri-calendar-check-line"></i></div>
            <h3 class="confirm-modal-title">Confirma tu cita</h3>
            <p class="confirm-modal-text">
              Para continuar con el servicio y quedar agendado, debes pagar el <strong>{{ Math.round(PORCENTAJE_ANTICIPO * 100) }}% de anticipo</strong>.
              El resto se paga al finalizar el trabajo, coordinado directamente con nuestro equipo.
            </p>

            <div class="cotiz-anticipo-resumen">
              <div><span>Total del servicio</span><strong>{{ formatCOP(cotizacionAAnticipar.total_estimado) }}</strong></div>
              <div class="destacado"><span>Anticipo a pagar ahora</span><strong>{{ formatCOP(montoAnticipo(cotizacionAAnticipar.total_estimado)) }}</strong></div>
            </div>

            <div class="form-group" style="text-align:left;margin:16px 0;">
              <label class="form-label">Método de pago</label>
              <select v-model="metodoAnticipo" class="form-control">
                <option value="tarjeta">Tarjeta de crédito o débito</option>
                <option value="nequi">Nequi</option>
                <option value="daviplata">Daviplata</option>
                <option value="transferencia">Transferencia / Consignación</option>
              </select>
            </div>

            <div class="confirm-modal-actions">
              <button class="btn btn-primary btn-lg btn-block" @click="confirmarAnticipo">
                <i class="ri-bank-card-line"></i> Pagar Anticipo y Confirmar Cita
              </button>
              <button class="btn btn-outline-red btn-block" @click="cancelarAnticipo">Cancelar</button>
            </div>
          </template>

          <template v-else>
            <div class="confirm-modal-icon"><i class="ri-loader-4-line" style="animation:spin 1s linear infinite;"></i></div>
            <h3 class="confirm-modal-title">Procesando tu pago...</h3>
            <p class="confirm-modal-text">No cierres esta ventana.</p>
          </template>
        </div>
      </Transition>
    </div>
  </Transition>

  <!-- Confirmación de anticipo pagado -->
  <Transition name="confirm-modal-fade">
    <div v-if="anticipoConfirmado" class="confirm-modal-overlay" @click.self="anticipoConfirmado = null">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="anticipoConfirmado = null"><i class="ri-close-line"></i></button>
          <div class="confirm-modal-icon" style="background:rgba(39,174,96,0.1);color:var(--success);"><i class="ri-checkbox-circle-fill"></i></div>
          <h3 class="confirm-modal-title">¡Cita confirmada!</h3>
          <p class="confirm-modal-text">
            Pagaste {{ formatCOP(anticipoConfirmado.monto) }} de anticipo para la cotización <strong>{{ anticipoConfirmado.numero }}</strong>.
            <template v-if="anticipoConfirmado.fecha"> Te esperamos el <strong>{{ anticipoConfirmado.fecha }}</strong>.</template>
            Nuestro equipo te contactará para coordinar los detalles finales.
          </p>
          <button class="btn btn-primary btn-lg btn-block" @click="anticipoConfirmado = null">Entendido</button>
        </div>
      </Transition>
    </div>
  </Transition>
</template>
