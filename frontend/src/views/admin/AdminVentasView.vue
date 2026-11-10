<script setup>
// Panel de Ventas -> tablas `ventas` + `detalle_ventas` + `pagos` (esta última sin usar en
// ningún otro lugar de la app hasta ahora). Reemplaza el placeholder que llamaba a una API
// muerta (api.get('/admin/ventas')) — ahora lee del store reactivo `ventas`, así que un pedido
// que se acaba de pagar en el checkout aparece aquí al instante, igual que ya pasa con
// Productos/Inventario/Cotizaciones.
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useVentasStore, ESTADOS_VENTA, labelEstadoVenta } from '../../stores/ventas'
import { useAuthStore } from '../../stores/auth'
import { useToast } from '../../composables/useToast'
import { formatCOP, formatDateTime, extraerDireccionEntrega, extraerNotaClienteVenta, fechaLocalISO } from '../../composables/useFormat'
import api from '../../services/api'

const catalog = useCatalogStore()
const ventasStore = useVentasStore()
const auth = useAuthStore()
const { showToast } = useToast()

// Cada venta ya trae su `usuario` anidado (viene del backend) - se arma un mapa en vez de pedir
// una lista aparte de clientes, así no depende de una página admin de "Clientes" que el usuario
// declinó explícitamente.
const usuarioPorId = computed(() => {
  const map = {}
  ventasStore.ventas.forEach((v) => { if (v.usuario) map[v.id_usuario] = v.usuario })
  return map
})
function clienteDe(id_usuario) {
  return usuarioPorId.value[id_usuario] || null
}
function nombreCliente(id_usuario) {
  const u = clienteDe(id_usuario)
  return u ? `${u.nombre} ${u.apellido}` : 'Cliente'
}
function inicialesCliente(id_usuario) {
  const u = clienteDe(id_usuario)
  return u ? ((u.nombre[0] || '') + (u.apellido[0] || '')).toUpperCase() : '?'
}
function itemsDe(id_venta) {
  return ventasStore.detalleVentas.filter((d) => d.id_venta === id_venta)
}

// ── Estadísticas ─────────────────────────────────────────────
// Simplificado a lo que el negocio de verdad usa día a día: un pedido está "En proceso" (recién
// pagado, todavía no se le pasa a la transportadora) o "Despachado" (ya se le entregó el pedido a
// la empresa de envíos — OJO: no significa que el cliente ya lo recibió, eso pasa por fuera del
// sitio vía correo/WhatsApp con la guía de la transportadora). El resto de estados intermedios
// del ENUM real (confirmado/preparando/despacho/enviado) no se usan desde la UI.
const ESTADOS_TERMINALES = ['entregado', 'cancelado', 'devuelto', 'garantia']
function esPedidoActivo(venta) {
  return !ESTADOS_TERMINALES.includes(venta.estado)
}

// Un pedido admite cancelar/devolver mientras no esté ya cerrado por esa misma vía - "en proceso"
// se cancela (nunca se despachó), "entregado" se devuelve (el cliente ya lo tenía en sus manos y
// lo regresa). Nunca los dos a la vez, así el admin no tiene que escoger entre dos opciones donde
// solo una tiene sentido según en qué punto va el pedido.
function puedeCancelarODevolver(venta) {
  return !['cancelado', 'devuelto'].includes(venta.estado)
}

const hoyStr = fechaLocalISO()
const ventasHoy = computed(() => ventasStore.ventas.filter((v) => v.fecha.startsWith(hoyStr)))
const totalVentasHoy = computed(() => ventasHoy.value.reduce((s, v) => s + v.total, 0))
const totalEnProceso = computed(() => ventasStore.ventas.filter(esPedidoActivo).length)
const totalEntregados = computed(() => ventasStore.ventas.filter((v) => v.estado === 'entregado').length)
// Separados (antes un solo número combinado) - "cancelado" y "devuelto" son cosas distintas para
// el negocio (nunca se despachó vs. el cliente ya lo tenía y lo regresó) y se estaban confundiendo.
const totalCancelados = computed(() => ventasStore.ventas.filter((v) => v.estado === 'cancelado').length)
const totalDevueltos = computed(() => ventasStore.ventas.filter((v) => v.estado === 'devuelto').length)

// ── Ventas de los últimos 30 días ──────────────────────────────
const CHART_W = 600
const CHART_H = 190
const CHART_PAD = 20

const ultimos30Dias = computed(() => {
  const dias = []
  for (let i = 29; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    dias.push(fechaLocalISO(d))
  }
  return dias
})
const ventasDelPeriodo = computed(() => {
  const desde = ultimos30Dias.value[0]
  return ventasStore.ventas.filter((v) => v.fecha.slice(0, 10) >= desde)
})
const totalesPorDia = computed(() =>
  ultimos30Dias.value.map((dia) => ventasStore.ventas.filter((v) => v.fecha.startsWith(dia)).reduce((s, v) => s + v.total, 0))
)
const ingresosTotales30d = computed(() => totalesPorDia.value.reduce((s, v) => s + v, 0))
const promedioDiario30d = computed(() => Math.round(ingresosTotales30d.value / 30))
const productoMasVendido30d = computed(() => {
  const idsVenta = new Set(ventasDelPeriodo.value.map((v) => v.id_venta))
  const totales = {}
  ventasStore.detalleVentas.forEach((d) => {
    if (!idsVenta.has(d.id_venta)) return
    totales[d.id_producto] = (totales[d.id_producto] || 0) + d.cantidad
  })
  const idsOrdenados = Object.entries(totales).sort((a, b) => b[1] - a[1])
  if (!idsOrdenados.length) return null
  return catalog.getProductById(Number(idsOrdenados[0][0]))
})

const chartPuntos = computed(() => {
  const vals = totalesPorDia.value
  const max = Math.max(...vals, 1)
  return vals.map((v, i) => ({
    x: CHART_PAD + (i * (CHART_W - CHART_PAD * 2)) / (vals.length - 1),
    y: CHART_H - CHART_PAD - (v / max) * (CHART_H - CHART_PAD * 2),
    valor: v,
    dia: ultimos30Dias.value[i],
  }))
})
const lineaPath = computed(() => chartPuntos.value.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x},${p.y}`).join(' '))
const areaPath = computed(() => {
  const pts = chartPuntos.value
  if (!pts.length) return ''
  const base = CHART_H - CHART_PAD
  return `M${pts[0].x},${base} ` + pts.map((p) => `L${p.x},${p.y}`).join(' ') + ` L${pts[pts.length - 1].x},${base} Z`
})
const chartEtiquetas = computed(() => {
  const idxs = [0, 6, 12, 18, 24, 29]
  return idxs.map((i) => ({
    x: chartPuntos.value[i].x,
    label: new Date(chartPuntos.value[i].dia + 'T00:00:00').toLocaleDateString('es-CO', { day: 'numeric', month: 'short' }),
  }))
})

// ── Dona de estado de pedidos ───────────────────────────────
const donaSegmentos = computed(() => {
  const total = ventasStore.ventas.length || 1
  const grupos = [
    { key: 'proceso', label: 'En proceso', color: 'var(--info)', count: totalEnProceso.value },
    { key: 'entregado', label: 'Despachados', color: 'var(--success)', count: totalEntregados.value },
    { key: 'cancelado', label: 'Cancelados', color: 'var(--primary)', count: totalCancelados.value },
    { key: 'devuelto', label: 'Devueltos', color: '#8E44AD', count: totalDevueltos.value },
  ]
  const RADIO = 60
  const CIRCUNFERENCIA = 2 * Math.PI * RADIO
  let acumulado = 0
  return grupos
    .filter((g) => g.count > 0)
    .map((g) => {
      const pct = g.count / total
      const dash = pct * CIRCUNFERENCIA
      const seg = { ...g, pct: Math.round(pct * 100), dasharray: `${dash} ${CIRCUNFERENCIA - dash}`, offset: -acumulado }
      acumulado += dash
      return seg
    })
})
const RADIO_DONA = 60
const CIRC_DONA = 2 * Math.PI * RADIO_DONA

// ── Pestañas + filtros de la tabla de pedidos ───────────────────
// 5 pestañas en vez de las 2 de antes ("En proceso" / "Entregados", donde "Entregados" en
// realidad mezclaba despachados+cancelados+devueltos sin distinguirlos) - ahora cada pestaña
// muestra exactamente un estado real, más "Todos" para no perder la vista completa.
const tabActiva = ref('proceso')
const TAB_TITULOS = {
  todos: 'Todos los pedidos',
  proceso: 'Pedidos en proceso',
  entregado: 'Pedidos despachados',
  cancelado: 'Pedidos cancelados',
  devuelto: 'Pedidos devueltos',
}
const busqueda = ref('')
const filtroMetodo = ref('')
const fechaDesde = ref('')
const fechaHasta = ref('')
const paginaActual = ref(1)
const POR_PAGINA = 8

function coincideTab(v) {
  if (tabActiva.value === 'todos') return true
  if (tabActiva.value === 'proceso') return esPedidoActivo(v)
  return v.estado === tabActiva.value // 'entregado' | 'cancelado' | 'devuelto'
}

const pedidosFiltrados = computed(() => {
  const term = busqueda.value.trim().toLowerCase()
  return [...ventasStore.ventas]
    .filter((v) => {
      const coincideTerm = !term || v.numero_venta.toLowerCase().includes(term) || nombreCliente(v.id_usuario).toLowerCase().includes(term)
      const coincideMetodo = !filtroMetodo.value || v.metodo_pago === filtroMetodo.value
      const coincideDesde = !fechaDesde.value || v.fecha.slice(0, 10) >= fechaDesde.value
      const coincideHasta = !fechaHasta.value || v.fecha.slice(0, 10) <= fechaHasta.value
      return coincideTab(v) && coincideTerm && coincideMetodo && coincideDesde && coincideHasta
    })
    .sort((a, b) => new Date(b.fecha.replace(' ', 'T')) - new Date(a.fecha.replace(' ', 'T')))
})
const totalPaginas = computed(() => Math.max(1, Math.ceil(pedidosFiltrados.value.length / POR_PAGINA)))
const pedidosPagina = computed(() => {
  const inicio = (paginaActual.value - 1) * POR_PAGINA
  return pedidosFiltrados.value.slice(inicio, inicio + POR_PAGINA)
})
watch([tabActiva, busqueda, filtroMetodo, fechaDesde, fechaHasta], () => { paginaActual.value = 1 })

function limpiarFiltros() {
  busqueda.value = ''
  filtroMetodo.value = ''
  fechaDesde.value = ''
  fechaHasta.value = ''
}
function irAPagina(n) { paginaActual.value = Math.min(Math.max(1, n), totalPaginas.value) }

// Las 5 tarjetas de arriba y las 5 pestañas de la tabla comparten esta misma función - filtran
// y de paso bajan la pantalla hasta la tabla, para que el clic se sienta como que "hizo algo" de
// una vez, no solo cambió un estado invisible más abajo.
const tablaRef = ref(null)
function filtrarPor(tab) {
  tabActiva.value = tab
  nextTick(() => tablaRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
function verVentasHoy() {
  fechaDesde.value = hoyStr
  fechaHasta.value = hoyStr
  filtrarPor('todos')
}

// Simplificado a 2 acciones del día a día (en vez de exponer los 9 estados uno por uno): marcar
// entregado, o cancelar/devolver (que es lo único que de verdad repone stock y requiere confirmar).
// Un pedido de envío pide número de guía primero (se le manda al cliente por correo); uno de
// recogida no pasa por transportadora, así que sigue siendo un solo clic.
const TRANSPORTADORAS = ['Servientrega', 'Coordinadora', 'TCC', 'Envía', 'Interrapidísimo', 'Otra']
const pedidoADespachar = ref(null)
const guiaForm = ref({ numero_guia: '', transportadora: '' })

function abrirMarcarEntregado(venta) {
  if (venta.metodo_envio === 'recogida') {
    marcarEntregado(venta)
    return
  }
  pedidoADespachar.value = venta
  guiaForm.value = { numero_guia: '', transportadora: '' }
}

async function marcarEntregado(venta) {
  try {
    await ventasStore.actualizarEstadoVenta(venta.id_venta, 'entregado')
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo actualizar el pedido.', 'danger')
    return
  }
  showToast(`Pedido ${venta.numero_venta} marcado como entregado. Se notificó al cliente por correo.`, 'success')
}

async function confirmarDespacho() {
  if (!guiaForm.value.numero_guia.trim()) {
    showToast('Ingresa el número de guía.', 'danger')
    return
  }
  if (!guiaForm.value.transportadora) {
    showToast('Selecciona la transportadora.', 'danger')
    return
  }
  const venta = pedidoADespachar.value
  try {
    await ventasStore.actualizarEstadoVenta(venta.id_venta, 'entregado', {
      numero_guia: guiaForm.value.numero_guia.trim(),
      transportadora: guiaForm.value.transportadora,
    })
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo actualizar el pedido.', 'danger')
    return
  }
  showToast(`Pedido ${venta.numero_venta} marcado como despachado. Se notificó al cliente por correo con la guía.`, 'success')
  pedidoADespachar.value = null
}

// El pedido ya "sabe" cuál de las dos acciones aplica según dónde va (ver puedeCancelarODevolver):
// todavía en proceso -> cancelar, ya entregado -> devolver. El admin no elige entre las dos, solo
// confirma la única que tiene sentido en ese momento - así no hay opción "rara" que no aplica.
const pedidoACancelar = ref(null)
const tipoAccionCancelar = computed(() => (pedidoACancelar.value?.estado === 'entregado' ? 'devuelto' : 'cancelado'))
const motivoCancelar = ref('')
// Suma de lo que ya se cobró de verdad (pagos "completado") en este pedido - hoy el sistema no
// devuelve esa plata solo (no hay pasarela real conectada), así que el admin necesita ver el
// monto para saber cuánto tiene que coordinar por fuera con el cliente. Antes esta cifra solo
// estaba visible en el historial de pagos del detalle expandido, no en el momento de confirmar.
const montoReembolsoPendiente = computed(() =>
  (pedidoACancelar.value?.pagos || []).filter((p) => p.estado === 'completado').reduce((s, p) => s + p.valor, 0)
)
function abrirCancelarPedido(venta) {
  pedidoACancelar.value = venta
  motivoCancelar.value = ''
}
async function confirmarCancelacion() {
  const venta = pedidoACancelar.value
  const tipo = tipoAccionCancelar.value
  if (!motivoCancelar.value.trim()) {
    showToast('Escribe el motivo antes de continuar.', 'danger')
    return
  }
  try {
    await ventasStore.actualizarEstadoVenta(venta.id_venta, tipo, { motivo: motivoCancelar.value.trim() })
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo actualizar el pedido.', 'danger')
    return
  }
  showToast(`Pedido ${venta.numero_venta} marcado como ${ESTADOS_VENTA[tipo].label.toLowerCase()} — el stock se repuso en Inventario y se notificó al cliente por correo.`, 'info')
  pedidoACancelar.value = null
}

// ── Detalle expandible: notas internas ──────────────────────────
// Sin fecha estimada de entrega: no tiene lógica real en el flujo del negocio (el seguimiento de
// envío pasa por fuera del sitio, con la transportadora y por correo/WhatsApp).
const pedidoExpandido = ref(null)
const notasForm = ref({ notas_internas: '' })
function toggleDetalle(venta) {
  if (pedidoExpandido.value === venta.id_venta) {
    pedidoExpandido.value = null
    return
  }
  pedidoExpandido.value = venta.id_venta
  notasForm.value = { notas_internas: venta.notas_internas || '' }
}
async function guardarNotas(venta) {
  try {
    await ventasStore.actualizarNotasVenta(venta.id_venta, {
      notas_internas: notasForm.value.notas_internas.trim() || null,
    })
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudieron guardar las notas.', 'danger')
    return
  }
  showToast('Notas del pedido guardadas.', 'success')
}

// ── Productos más vendidos (top 5, por unidades) ───────────────
const productosMasVendidos = computed(() => {
  const totales = {}
  ventasStore.detalleVentas.forEach((d) => {
    totales[d.id_producto] = (totales[d.id_producto] || 0) + d.cantidad
  })
  const max = Math.max(...Object.values(totales), 1)
  return Object.entries(totales)
    .map(([id, unidades]) => ({ producto: catalog.getProductById(Number(id)), unidades, pct: Math.round((unidades / max) * 100) }))
    .filter((x) => x.producto)
    .sort((a, b) => b.unidades - a.unidades)
    .slice(0, 5)
})

// ── Clientes frecuentes (top 5, por total comprado) ─────────────
const clientesFrecuentes = computed(() => {
  const totales = {}
  ventasStore.ventas.forEach((v) => {
    if (!totales[v.id_usuario]) totales[v.id_usuario] = { id_usuario: v.id_usuario, total: 0, pedidos: 0 }
    totales[v.id_usuario].total += v.total
    totales[v.id_usuario].pedidos += 1
  })
  return Object.values(totales)
    .sort((a, b) => b.total - a.total)
    .slice(0, 5)
})

// ── Exportar CSV (real, sin backend) ────────────────────────────
function exportarCSV() {
  const filas = [['Número', 'Cliente', 'Email', 'Fecha', 'Total', 'Estado', 'Método de pago', 'Método de envío', 'Guía']]
  pedidosFiltrados.value.forEach((v) => {
    filas.push([
      v.numero_venta, nombreCliente(v.id_usuario), clienteDe(v.id_usuario)?.email || '', v.fecha, v.total,
      labelEstadoVenta(v), v.metodo_pago, v.metodo_envio === 'recogida' ? 'Recogida en tienda' : 'Envío',
      v.numero_guia || '',
    ])
  })
  const csv = filas.map((fila) => fila.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `pedidos_${fechaLocalISO()}.csv`
  a.click()
  URL.revokeObjectURL(url)
  showToast('Reporte CSV descargado.', 'success')
}

// ── Modal: nueva venta manual ────────────────────────────────
const mostrarModalVenta = ref(false)
// Lista corta solo para este selector - no es la página admin de "Clientes" que el usuario
// declinó (ver memoria del proyecto), se pide una sola vez al entrar a esta vista.
const clientesDisponibles = ref([])
onMounted(async () => {
  const { data } = await api.get('/usuarios/clientes')
  clientesDisponibles.value = data
})
const formVenta = ref({ id_usuario: '', metodo_pago: 'efectivo', metodo_envio: 'recogida', notas_cliente: '' })
const itemsVenta = ref([])
const itemSeleccionado = ref({ id_producto: '', cantidad: 1 })

function abrirNuevaVenta() {
  formVenta.value = { id_usuario: '', metodo_pago: 'efectivo', metodo_envio: 'recogida', notas_cliente: '' }
  itemsVenta.value = []
  itemSeleccionado.value = { id_producto: '', cantidad: 1 }
  mostrarModalVenta.value = true
}
function agregarItemVenta() {
  if (!itemSeleccionado.value.id_producto || !itemSeleccionado.value.cantidad) return
  const producto = catalog.getProductById(Number(itemSeleccionado.value.id_producto))
  if (!producto) return
  const existente = itemsVenta.value.find((i) => i.id_producto === producto.id_producto)
  if (existente) {
    existente.cantidad += Number(itemSeleccionado.value.cantidad)
  } else {
    itemsVenta.value.push({ id_producto: producto.id_producto, nombre: producto.nombre, precio_venta: producto.precio_venta, cantidad: Number(itemSeleccionado.value.cantidad) })
  }
  itemSeleccionado.value = { id_producto: '', cantidad: 1 }
}
function quitarItemVenta(id_producto) {
  itemsVenta.value = itemsVenta.value.filter((i) => i.id_producto !== id_producto)
}
const totalVentaManual = computed(() => itemsVenta.value.reduce((s, i) => s + i.precio_venta * i.cantidad, 0))

async function guardarVentaManual() {
  if (!formVenta.value.id_usuario) {
    showToast('Selecciona el cliente.', 'error')
    return
  }
  if (!itemsVenta.value.length) {
    showToast('Agrega al menos un producto.', 'error')
    return
  }
  let venta
  try {
    venta = await ventasStore.crearVenta({
      id_usuario: Number(formVenta.value.id_usuario),
      items: itemsVenta.value,
      metodo_pago: formVenta.value.metodo_pago,
      metodo_envio: formVenta.value.metodo_envio,
      notas_cliente: formVenta.value.notas_cliente.trim() || null,
    })
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo registrar el pedido.', 'danger')
    return
  }
  showToast(`Pedido ${venta.numero_venta} registrado correctamente.`, 'success')
  mostrarModalVenta.value = false
}
</script>

<template>
  <div class="admin-page-header">
    <div>
      <h1>Pedidos y Ventas</h1>
      <p>Administra los pedidos de la tienda, su estado de entrega y el historial de pagos.</p>
    </div>
    <div style="display:flex;gap:10px;flex-wrap:wrap;">
      <button type="button" class="btn btn-outline-red" @click="exportarCSV"><i class="ri-download-2-line"></i> Exportar CSV</button>
      <button type="button" class="btn btn-primary" @click="abrirNuevaVenta"><i class="ri-add-line"></i> Nueva venta</button>
    </div>
  </div>

  <!-- ESTADÍSTICAS — cada tarjeta es un botón real: filtra la tabla de abajo y baja hasta ella -->
  <div class="admin-stats-grid ventas-stats-grid">
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="verVentasHoy">
      <div class="admin-stat-top"><span class="admin-stat-icon icon-rojo"><i class="ri-bank-card-2-line"></i></span></div>
      <strong class="admin-stat-value">{{ formatCOP(totalVentasHoy) }}</strong>
      <span class="admin-stat-label">Ventas de hoy</span>
    </button>
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="filtrarPor('proceso')">
      <div class="admin-stat-top"><span class="admin-stat-icon icon-azul"><i class="ri-truck-line"></i></span></div>
      <strong class="admin-stat-value">{{ totalEnProceso }}</strong>
      <span class="admin-stat-label">En proceso</span>
    </button>
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="filtrarPor('entregado')">
      <div class="admin-stat-top"><span class="admin-stat-icon icon-verde"><i class="ri-checkbox-circle-line"></i></span></div>
      <strong class="admin-stat-value">{{ totalEntregados }}</strong>
      <span class="admin-stat-label">Despachados</span>
    </button>
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="filtrarPor('cancelado')">
      <div class="admin-stat-top"><span class="admin-stat-icon icon-rojo"><i class="ri-close-circle-line"></i></span></div>
      <strong class="admin-stat-value">{{ totalCancelados }}</strong>
      <span class="admin-stat-label">Cancelados</span>
    </button>
    <button type="button" class="admin-stat-card admin-stat-card-btn" @click="filtrarPor('devuelto')">
      <div class="admin-stat-top"><span class="admin-stat-icon icon-morado"><i class="ri-arrow-go-back-line"></i></span></div>
      <strong class="admin-stat-value">{{ totalDevueltos }}</strong>
      <span class="admin-stat-label">Devueltos</span>
    </button>
  </div>

  <!-- GRÁFICO 30 DÍAS + DONA DE ESTADOS -->
  <div class="admin-bottom-grid ventas-top-grid">
    <div class="admin-card">
      <div class="admin-card-header"><h2>Ventas de los últimos 30 días</h2></div>
      <div style="padding: 16px 20px 8px;">
        <svg :viewBox="`0 0 ${CHART_W} ${CHART_H}`" class="ventas-chart">
          <line v-for="i in 4" :key="i" x1="20" :y1="20 + i * 35" :x2="CHART_W - 20" :y2="20 + i * 35" stroke="#E0E0E0" stroke-dasharray="4 4" />
          <path :d="areaPath" fill="url(#ventasGrad)" />
          <path :d="lineaPath" fill="none" stroke="var(--primary)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
          <defs>
            <linearGradient id="ventasGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="var(--primary)" stop-opacity="0.22" />
              <stop offset="100%" stop-color="var(--primary)" stop-opacity="0" />
            </linearGradient>
          </defs>
        </svg>
        <div class="ventas-chart-labels">
          <span v-for="et in chartEtiquetas" :key="et.label">{{ et.label }}</span>
        </div>
        <div class="ventas-chart-resumen">
          <div><strong>{{ formatCOP(ingresosTotales30d) }}</strong><span>Ingresos totales</span></div>
          <div><strong>{{ formatCOP(promedioDiario30d) }}</strong><span>Promedio diario</span></div>
          <div><strong :title="productoMasVendido30d?.nombre">{{ productoMasVendido30d?.nombre || 'Sin ventas aún' }}</strong><span>Producto más vendido</span></div>
        </div>
      </div>
    </div>

    <div class="admin-card">
      <div class="admin-card-header"><h2>Estado de los pedidos</h2></div>
      <div class="ventas-dona-wrap">
        <svg viewBox="0 0 160 160" class="ventas-dona">
          <circle cx="80" cy="80" :r="RADIO_DONA" fill="none" stroke="var(--off-white)" stroke-width="18" />
          <circle
            v-for="seg in donaSegmentos"
            :key="seg.key"
            cx="80" cy="80" :r="RADIO_DONA" fill="none"
            :stroke="seg.color" stroke-width="18"
            :stroke-dasharray="seg.dasharray"
            :stroke-dashoffset="seg.offset"
            transform="rotate(-90 80 80)"
          >
            <title>{{ seg.label }}: {{ seg.pct }}%</title>
          </circle>
          <text x="80" y="76" text-anchor="middle" class="ventas-dona-total">{{ ventasStore.ventas.length }}</text>
          <text x="80" y="94" text-anchor="middle" class="ventas-dona-total-label">pedidos</text>
        </svg>
        <div class="ventas-dona-legend">
          <div v-for="seg in donaSegmentos" :key="seg.key" class="ventas-dona-legend-item">
            <i class="ventas-dona-dot" :style="{ background: seg.color }"></i>
            <span>{{ seg.label }}</span>
            <strong>{{ seg.pct }}%</strong>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- PESTAÑAS: TODOS / EN PROCESO / DESPACHADOS / CANCELADOS / DEVUELTOS -->
  <div class="admin-tabs">
    <button type="button" class="admin-tab" :class="{ active: tabActiva === 'todos' }" @click="filtrarPor('todos')">
      <i class="ri-list-check-2"></i> Todos <span class="badge-count">{{ ventasStore.ventas.length }}</span>
    </button>
    <button type="button" class="admin-tab" :class="{ active: tabActiva === 'proceso' }" @click="filtrarPor('proceso')">
      <i class="ri-truck-line"></i> En proceso <span class="badge-count">{{ totalEnProceso }}</span>
    </button>
    <button type="button" class="admin-tab" :class="{ active: tabActiva === 'entregado' }" @click="filtrarPor('entregado')">
      <i class="ri-checkbox-circle-line"></i> Despachados <span class="badge-count">{{ totalEntregados }}</span>
    </button>
    <button type="button" class="admin-tab" :class="{ active: tabActiva === 'cancelado' }" @click="filtrarPor('cancelado')">
      <i class="ri-close-circle-line"></i> Cancelados <span class="badge-count">{{ totalCancelados }}</span>
    </button>
    <button type="button" class="admin-tab" :class="{ active: tabActiva === 'devuelto' }" @click="filtrarPor('devuelto')">
      <i class="ri-arrow-go-back-line"></i> Devueltos <span class="badge-count">{{ totalDevueltos }}</span>
    </button>
  </div>

  <!-- FILTROS -->
  <div class="admin-filters-bar">
    <div class="search-input-wrap">
      <input v-model="busqueda" type="search" class="form-control" placeholder="Buscar por número o cliente..." />
    </div>
    <select v-model="filtroMetodo">
      <option value="">Método de pago</option>
      <option value="efectivo">Efectivo</option>
      <option value="transferencia">Transferencia</option>
      <option value="tarjeta">Tarjeta</option>
      <option value="nequi">Nequi</option>
      <option value="daviplata">Daviplata</option>
      <option value="contraentrega">Contraentrega</option>
    </select>
    <input v-model="fechaDesde" type="date" class="form-control" style="max-width:150px;" title="Desde" />
    <input v-model="fechaHasta" type="date" class="form-control" style="max-width:150px;" title="Hasta" />
    <button type="button" class="btn btn-outline-red btn-sm" @click="limpiarFiltros">
      <i class="ri-filter-off-line"></i> Limpiar
    </button>
  </div>

  <!-- TABLA DE PEDIDOS -->
  <div class="admin-card" ref="tablaRef">
    <div class="admin-card-header">
      <h2>{{ TAB_TITULOS[tabActiva] }}</h2>
      <span>{{ pedidosFiltrados.length }} registros encontrados</span>
    </div>

    <div v-if="!pedidosPagina.length" class="admin-empty">
      <i class="ri-inbox-line"></i>
      <p>No hay pedidos que coincidan con estos filtros.</p>
    </div>

    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>Pedido</th>
            <th>Cliente</th>
            <th>Fecha</th>
            <th>Productos</th>
            <th>Total</th>
            <th>Estado</th>
            <th>Pago</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="v in pedidosPagina" :key="v.id_venta">
            <tr>
              <td><strong>{{ v.numero_venta }}</strong><br /><span v-if="v.id_cotizacion" class="ventas-cotiz-tag">De cotización</span></td>
              <td>
                <div class="admin-prod-info">
                  <strong>{{ nombreCliente(v.id_usuario) }}</strong>
                  <span>{{ clienteDe(v.id_usuario)?.email }}</span>
                </div>
              </td>
              <td>{{ formatDateTime(v.fecha) }}</td>
              <td>
                <span v-if="!itemsDe(v.id_venta).length && v.id_cotizacion" class="badge badge-yellow"><i class="ri-calendar-event-line"></i> Anticipo de servicio</span>
                <template v-else>{{ itemsDe(v.id_venta).reduce((s, d) => s + d.cantidad, 0) }} und.</template>
              </td>
              <td>{{ formatCOP(v.total) }}</td>
              <td>
                <span class="badge" :class="ESTADOS_VENTA[v.estado].badge"><i :class="ESTADOS_VENTA[v.estado].icon"></i> {{ labelEstadoVenta(v) }}</span>
                <br v-if="v.metodo_envio === 'recogida'" /><span v-if="v.metodo_envio === 'recogida'" class="ventas-cotiz-tag">Recogida en tienda</span>
              </td>
              <td style="text-transform:capitalize;">{{ v.metodo_pago }}</td>
              <td>
                <div class="admin-actions-cell">
                  <button v-if="esPedidoActivo(v)" type="button" class="admin-action-btn" :title="v.metodo_envio === 'recogida' ? 'Marcar como entregado' : 'Marcar como despachado'" @click="abrirMarcarEntregado(v)">
                    <i class="ri-checkbox-circle-line"></i>
                  </button>
                  <button v-if="puedeCancelarODevolver(v)" type="button" class="admin-action-btn danger" :title="v.estado === 'entregado' ? 'Registrar devolución' : 'Cancelar pedido'" @click="abrirCancelarPedido(v)">
                    <i :class="v.estado === 'entregado' ? 'ri-arrow-go-back-line' : 'ri-close-circle-line'"></i>
                  </button>
                  <button type="button" class="admin-action-btn" title="Ver detalle" @click="toggleDetalle(v)">
                    <i :class="pedidoExpandido === v.id_venta ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'"></i>
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="pedidoExpandido === v.id_venta">
              <td colspan="8" class="ventas-detalle-cell">
                <div class="ventas-detalle-grid">
                  <div>
                    <h4>Productos</h4>
                    <div v-for="item in itemsDe(v.id_venta)" :key="item.id_detalle" class="ventas-detalle-item">
                      <span>{{ catalog.getProductById(item.id_producto)?.nombre || 'Producto eliminado' }} <span class="ventas-detalle-item-meta">x{{ item.cantidad }}</span></span>
                      <strong class="ventas-detalle-monto">{{ formatCOP(item.subtotal) }}</strong>
                    </div>
                    <p v-if="extraerDireccionEntrega(v.notas_cliente)" class="ventas-detalle-direccion"><i class="ri-map-pin-line"></i> <strong>Entregar en:</strong> {{ extraerDireccionEntrega(v.notas_cliente) }}</p>
                    <p v-if="v.numero_guia" class="ventas-detalle-direccion"><i class="ri-truck-line"></i> <strong>Guía:</strong> {{ v.numero_guia }}<span v-if="v.transportadora"> — {{ v.transportadora }}</span></p>
                    <p v-if="extraerNotaClienteVenta(v.notas_cliente)" class="ventas-detalle-nota"><strong>Nota del cliente:</strong> {{ extraerNotaClienteVenta(v.notas_cliente) }}</p>

                    <h4 style="margin-top:16px;">Historial de pago</h4>
                    <div v-for="p in ventasStore.getPagosDeVenta(v.id_venta)" :key="p.id_pago" class="ventas-detalle-item">
                      <span><i class="ri-bank-card-line"></i> {{ p.metodo_pago }} — {{ formatDateTime(p.fecha) }}</span>
                      <span>
                        <strong class="ventas-detalle-monto">{{ formatCOP(p.valor) }}</strong>
                        <span class="badge" :class="p.estado === 'completado' ? 'badge-green' : p.estado === 'reversado' ? 'badge-red' : 'badge-yellow'" style="margin-left:6px;">{{ p.estado }}</span>
                      </span>
                    </div>
                  </div>
                  <div>
                    <h4>Notas internas (solo el equipo)</h4>
                    <textarea v-model="notasForm.notas_internas" class="form-control" rows="3" placeholder="Ej. Cliente pidió llamar antes de entregar..."></textarea>
                    <button type="button" class="btn btn-primary btn-sm" style="margin-top:12px;" @click="guardarNotas(v)"><i class="ri-save-line"></i> Guardar notas</button>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <div v-if="totalPaginas > 1" class="admin-pagination">
      <button type="button" class="admin-page-btn" :disabled="paginaActual === 1" @click="irAPagina(paginaActual - 1)"><i class="ri-arrow-left-s-line"></i></button>
      <button v-for="n in totalPaginas" :key="n" type="button" class="admin-page-btn" :class="{ active: n === paginaActual }" @click="irAPagina(n)">{{ n }}</button>
      <button type="button" class="admin-page-btn" :disabled="paginaActual === totalPaginas" @click="irAPagina(paginaActual + 1)"><i class="ri-arrow-right-s-line"></i></button>
    </div>
  </div>

  <!-- PRODUCTOS MÁS VENDIDOS + CLIENTES FRECUENTES -->
  <div class="admin-bottom-grid">
    <div class="admin-card">
      <div class="admin-card-header"><h2>Productos más vendidos</h2></div>
      <div class="admin-bar-list">
        <div v-if="!productosMasVendidos.length" class="admin-empty" style="padding:20px;">
          <p>Todavía no hay ventas registradas para calcular esto.</p>
        </div>
        <div v-for="pv in productosMasVendidos" :key="pv.producto.id_producto">
          <div class="admin-bar-row-top">
            <strong :title="pv.producto.nombre">{{ pv.producto.nombre }}</strong>
            <span>{{ pv.unidades }} und.</span>
          </div>
          <div class="admin-bar-track"><div class="admin-bar-fill" :style="{ width: pv.pct + '%' }"></div></div>
        </div>
      </div>
    </div>

    <div class="admin-card">
      <div class="admin-card-header"><h2>Clientes frecuentes</h2></div>
      <div class="ventas-clientes-list">
        <div v-if="!clientesFrecuentes.length" class="admin-empty" style="padding:20px;">
          <p>Todavía no hay clientes con compras.</p>
        </div>
        <div v-for="c in clientesFrecuentes" :key="c.id_usuario" class="ventas-cliente-item">
          <div class="ventas-cliente-avatar">{{ inicialesCliente(c.id_usuario) }}</div>
          <div class="ventas-cliente-info">
            <strong>{{ nombreCliente(c.id_usuario) }}</strong>
            <span>{{ c.pedidos }} pedido{{ c.pedidos === 1 ? '' : 's' }}</span>
          </div>
          <strong class="ventas-cliente-total">{{ formatCOP(c.total) }}</strong>
        </div>
      </div>
    </div>
  </div>

  <!-- MODAL: CANCELAR PEDIDO EN PROCESO / REGISTRAR DEVOLUCIÓN DE UNO YA ENTREGADO -->
  <Transition name="confirm-modal-fade">
    <div v-if="pedidoACancelar" class="confirm-modal-overlay" @click.self="pedidoACancelar = null">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="pedidoACancelar = null"><i class="ri-close-line"></i></button>
          <div class="confirm-modal-icon" style="background:rgba(192,57,43,0.1);color:var(--primary);">
            <i :class="tipoAccionCancelar === 'devuelto' ? 'ri-arrow-go-back-line' : 'ri-close-circle-line'"></i>
          </div>
          <h3 class="confirm-modal-title">{{ tipoAccionCancelar === 'devuelto' ? '¿Registrar la devolución de este pedido?' : '¿Cancelar este pedido?' }}</h3>
          <p class="confirm-modal-text">
            Pedido <strong>{{ pedidoACancelar?.numero_venta }}</strong>. El stock de los productos se repone automáticamente en Inventario
            {{ tipoAccionCancelar === 'devuelto' ? 'apenas confirmes que el cliente ya lo regresó' : '' }},
            y le llega un correo al cliente avisándole.
          </p>
          <div v-if="montoReembolsoPendiente > 0" class="alert alert-warning" style="text-align:left;">
            <i class="ri-money-dollar-circle-line"></i>
            <span>Este pedido tiene <strong>{{ formatCOP(montoReembolsoPendiente) }}</strong> ya cobrados. El sistema no lo devuelve solo — al confirmar, queda marcado como pendiente de reembolso y debes coordinar la devolución del dinero directamente con el cliente.</span>
          </div>
          <form @submit.prevent="confirmarCancelacion" style="text-align:left;">
            <div class="form-group">
              <label class="form-label required">Motivo</label>
              <input v-model="motivoCancelar" class="form-control" :placeholder="tipoAccionCancelar === 'devuelto' ? 'Ej. Producto defectuoso, talla equivocada...' : 'Ej. Cliente se arrepintió, no había stock real...'" required />
            </div>
            <div class="confirm-modal-actions">
              <button type="submit" class="btn btn-primary btn-lg btn-block" style="background:var(--danger);">
                <i :class="tipoAccionCancelar === 'devuelto' ? 'ri-arrow-go-back-line' : 'ri-close-circle-line'"></i>
                {{ tipoAccionCancelar === 'devuelto' ? 'Confirmar devolución' : 'Confirmar cancelación' }}
              </button>
              <button type="button" class="btn btn-outline-red btn-block" style="border-color:transparent;" @click="pedidoACancelar = null">Volver</button>
            </div>
          </form>
        </div>
      </Transition>
    </div>
  </Transition>

  <!-- MODAL: MARCAR COMO DESPACHADO (pide la guía, notifica al cliente) -->
  <Transition name="confirm-modal-fade">
    <div v-if="pedidoADespachar" class="confirm-modal-overlay" @click.self="pedidoADespachar = null">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="pedidoADespachar = null"><i class="ri-close-line"></i></button>
          <div class="confirm-modal-icon" style="background:rgba(39,174,96,0.1);color:var(--success);"><i class="ri-truck-line"></i></div>
          <h3 class="confirm-modal-title">Marcar pedido como despachado</h3>
          <p class="confirm-modal-text">
            Pedido <strong>{{ pedidoADespachar?.numero_venta }}</strong>. Ingresa los datos de la guía que te dio la transportadora — se los enviamos al cliente por correo apenas confirmes.
          </p>
          <form @submit.prevent="confirmarDespacho" style="text-align:left;">
            <div class="form-group">
              <label class="form-label required">Número de guía</label>
              <input v-model="guiaForm.numero_guia" class="form-control" placeholder="Ej. 123456789" required />
            </div>
            <div class="form-group">
              <label class="form-label required">Transportadora</label>
              <select v-model="guiaForm.transportadora" class="form-control" required>
                <option value="" disabled>Selecciona...</option>
                <option v-for="t in TRANSPORTADORAS" :key="t" :value="t">{{ t }}</option>
              </select>
            </div>
            <div class="confirm-modal-actions">
              <button type="submit" class="btn btn-primary btn-lg btn-block"><i class="ri-mail-send-line"></i> Despachar y notificar al cliente</button>
              <button type="button" class="btn btn-outline-red btn-block" @click="pedidoADespachar = null">Cancelar</button>
            </div>
          </form>
        </div>
      </Transition>
    </div>
  </Transition>

  <!-- MODAL: NUEVA VENTA MANUAL -->
  <Transition name="confirm-modal-fade">
    <div v-if="mostrarModalVenta" class="confirm-modal-overlay" @click.self="mostrarModalVenta = false">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal producto-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="mostrarModalVenta = false"><i class="ri-close-line"></i></button>
          <h3 class="confirm-modal-title">Nueva venta manual</h3>
          <p class="mb-16" style="text-align:center;color:var(--text-muted);font-size:0.82rem;">Para pedidos por teléfono o WhatsApp.</p>

          <div class="form-grid-2">
            <div class="form-group full">
              <label class="form-label required">Cliente</label>
              <select v-model="formVenta.id_usuario" class="form-control" required>
                <option value="" disabled>Selecciona un cliente</option>
                <option v-for="u in clientesDisponibles" :key="u.id_usuario" :value="u.id_usuario">{{ u.nombre }} {{ u.apellido }} — {{ u.email }}</option>
              </select>
            </div>

            <div class="form-group full">
              <label class="form-label">Agregar producto</label>
              <div style="display:flex;gap:8px;">
                <select v-model="itemSeleccionado.id_producto" class="form-control" style="flex:2;">
                  <option value="" disabled>Selecciona un producto</option>
                  <option v-for="p in catalog.productos" :key="p.id_producto" :value="p.id_producto">{{ p.nombre }} — {{ formatCOP(p.precio_venta) }}</option>
                </select>
                <input v-model.number="itemSeleccionado.cantidad" type="number" min="1" class="form-control" style="flex:1;" />
                <button type="button" class="btn btn-outline-red btn-sm" @click="agregarItemVenta"><i class="ri-add-line"></i></button>
              </div>
            </div>

            <div class="form-group full" v-if="itemsVenta.length">
              <div v-for="item in itemsVenta" :key="item.id_producto" class="ventas-detalle-item">
                <span>{{ item.nombre }} <span class="ventas-detalle-item-meta">x{{ item.cantidad }}</span></span>
                <span>{{ formatCOP(item.precio_venta * item.cantidad) }} <button type="button" class="admin-action-btn danger" style="width:24px;height:24px;margin-left:6px;" @click="quitarItemVenta(item.id_producto)"><i class="ri-close-line"></i></button></span>
              </div>
              <div class="ventas-detalle-item" style="font-weight:700;border-top:1px solid var(--border);padding-top:8px;margin-top:4px;">
                <span>Total</span>
                <span>{{ formatCOP(totalVentaManual) }}</span>
              </div>
            </div>

            <div class="form-group">
              <label class="form-label required">Método de envío</label>
              <select v-model="formVenta.metodo_envio" class="form-control" required>
                <option value="recogida">Recogida en tienda</option>
                <option value="envio">Envío</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label required">Método de pago</label>
              <select v-model="formVenta.metodo_pago" class="form-control" required>
                <option value="efectivo">Efectivo</option>
                <option value="transferencia">Transferencia</option>
                <option value="tarjeta">Tarjeta</option>
                <option value="nequi">Nequi</option>
                <option value="daviplata">Daviplata</option>
                <option value="contraentrega" :disabled="formVenta.metodo_envio !== 'envio'">Contraentrega{{ formVenta.metodo_envio !== 'envio' ? ' (solo envío)' : '' }}</option>
              </select>
            </div>

            <div class="form-group full">
              <label class="form-label">Notas del pedido</label>
              <textarea v-model="formVenta.notas_cliente" class="form-control" rows="2" placeholder="Ej. Entregar en la tarde, llamar antes..."></textarea>
            </div>

            <div class="form-actions full">
              <button type="button" class="btn btn-outline-red btn-sm" @click="mostrarModalVenta = false">Cancelar</button>
              <button type="button" class="btn btn-primary btn-sm" @click="guardarVentaManual"><i class="ri-save-line"></i> Registrar venta</button>
            </div>
          </div>
        </div>
      </Transition>
    </div>
  </Transition>
</template>

<style scoped>
.icon-azul { background: rgba(41,128,185,0.1); color: var(--info); }
.icon-verde { background: rgba(39,174,96,0.1); color: var(--success); }
.icon-morado { background: rgba(142,68,173,0.1); color: #8E44AD; }

/* Las 5 tarjetas de estadísticas de esta página son botones reales (filtran + bajan a la tabla),
   por eso necesitan su propio ancho de grilla (5, no las 4 que usa .admin-stats-grid en el resto
   del panel) y perder los estilos por defecto de <button>. */
.ventas-stats-grid { grid-template-columns: repeat(5, 1fr); }
@media (max-width: 1100px) { .ventas-stats-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 640px) { .ventas-stats-grid { grid-template-columns: 1fr 1fr; } }
.admin-stat-card-btn { display: block; width: 100%; text-align: left; cursor: pointer; transition: var(--transition); }
.admin-stat-card-btn:hover { transform: translateY(-2px); box-shadow: var(--shadow); border-color: transparent; }
.icon-alerta { background: rgba(243,156,18,0.12); color: var(--warning); }
.icon-rojo { background: rgba(192,57,43,0.1); color: var(--primary); }

.ventas-top-grid { grid-template-columns: 1.5fr 1fr; }

.ventas-chart { width: 100%; height: auto; display: block; }
.ventas-chart-labels { display: flex; justify-content: space-between; margin-top: 6px; padding: 0 6px; }
.ventas-chart-labels span { font-size: 0.7rem; color: var(--text-muted); font-weight: 600; }
.ventas-chart-resumen { display: flex; justify-content: space-between; gap: 10px; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--border); }
.ventas-chart-resumen div { flex: 1; text-align: center; min-width: 0; }
.ventas-chart-resumen strong { display: block; font-family: var(--font-main); font-weight: 800; font-size: 0.95rem; color: var(--secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ventas-chart-resumen span { font-size: 0.72rem; color: var(--text-muted); }

.ventas-dona-wrap { display: flex; align-items: center; gap: 20px; padding: 20px; flex-wrap: wrap; justify-content: center; }
.ventas-dona { width: 160px; height: 160px; flex-shrink: 0; }
.ventas-dona circle { transition: stroke-dasharray 0.3s; }
.ventas-dona-total { font-family: var(--font-main); font-weight: 800; font-size: 1.6rem; fill: var(--secondary); }
.ventas-dona-total-label { font-size: 0.7rem; fill: var(--text-muted); }
.ventas-dona-legend { display: flex; flex-direction: column; gap: 8px; flex: 1; min-width: 140px; }
.ventas-dona-legend-item { display: flex; align-items: center; gap: 8px; font-size: 0.8rem; color: var(--text); }
.ventas-dona-legend-item strong { margin-left: auto; color: var(--secondary); }
.ventas-dona-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; flex-shrink: 0; }

.ventas-cotiz-tag { font-size: 0.68rem; color: var(--text-muted); }

.ventas-detalle-cell { background: var(--off-white); padding: 20px !important; }
.ventas-detalle-grid { display: grid; grid-template-columns: 1.3fr 1fr; gap: 24px; }
.ventas-detalle-grid h4 { font-family: var(--font-main); font-weight: 700; font-size: 0.82rem; color: var(--secondary); margin-bottom: 8px; }
.ventas-detalle-item { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 7px 0; border-bottom: 1px solid var(--border); font-size: 0.83rem; }
.ventas-detalle-item:last-child { border-bottom: none; }
.ventas-detalle-item-meta { color: var(--text-muted); margin-left: 6px; }
.ventas-detalle-monto { font-family: var(--font-main); font-weight: 800; font-size: 0.92rem; color: var(--secondary); }
.ventas-detalle-nota { font-size: 0.82rem; color: var(--text-light); margin-top: 10px; }
.ventas-detalle-direccion { font-size: 0.82rem; color: var(--secondary); margin-top: 12px; background: white; border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px 12px; }
.ventas-detalle-direccion i { color: var(--primary); margin-right: 4px; }

.ventas-clientes-list { padding: 8px 20px 20px; display: flex; flex-direction: column; }
.ventas-cliente-item { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border); }
.ventas-cliente-item:last-child { border-bottom: none; }
.ventas-cliente-avatar { width: 38px; height: 38px; border-radius: 50%; background: var(--secondary); color: white; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 0.8rem; flex-shrink: 0; }
.ventas-cliente-info { flex: 1; display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.ventas-cliente-info strong { font-size: 0.85rem; color: var(--secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ventas-cliente-info span { font-size: 0.72rem; color: var(--text-muted); }
.ventas-cliente-total { font-size: 0.85rem; color: var(--secondary); flex-shrink: 0; }

@media (max-width: 1100px) {
  .ventas-top-grid { grid-template-columns: 1fr; }
  .ventas-detalle-grid { grid-template-columns: 1fr; }
}
</style>
