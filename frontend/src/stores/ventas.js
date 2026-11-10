import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import api from '../services/api'
import { useCatalogStore } from './catalog'
import { useAuthStore } from './auth'
import { useToast } from '../composables/useToast'

// Metadata de los 9 estados reales de `ventas.estado`, usada por los badges de AdminVentasView.
// La UI del admin solo pone dos de estos a mano (pendiente al crear la venta = "En proceso",
// entregado) más cancelado/devuelto — confirmado/preparando/despacho/enviado/garantia quedan acá
// documentados porque existen en el ENUM real, pero hoy ningún flujo del sitio los asigna.
export const ESTADOS_VENTA = {
  pendiente: { label: 'En proceso', badge: 'badge-yellow', icon: 'ri-truck-line' },
  confirmado: { label: 'Confirmado', badge: 'badge-blue', icon: 'ri-checkbox-circle-line' },
  preparando: { label: 'Preparando', badge: 'badge-blue', icon: 'ri-archive-2-line' },
  despacho: { label: 'En despacho', badge: 'badge-blue', icon: 'ri-truck-line' },
  enviado: { label: 'Enviado', badge: 'badge-blue', icon: 'ri-send-plane-line' },
  // El label de "entregado" no es fijo - depende de si el pedido era envío o recogida en tienda
  // (ver labelEstadoVenta). El de acá es solo el respaldo si por algo no hay metodo_envio.
  entregado: { label: 'Despachado', badge: 'badge-green', icon: 'ri-checkbox-circle-fill' },
  cancelado: { label: 'Cancelado', badge: 'badge-red', icon: 'ri-close-circle-line' },
  // Morado, no rojo - antes compartía color con "Cancelado" y no se distinguían de un vistazo en
  // la tabla (motivo real por el que el usuario pidió separarlos, 2026-08-25).
  devuelto: { label: 'Devuelto', badge: 'badge-purple', icon: 'ri-arrow-go-back-line' },
  garantia: { label: 'Garantía', badge: 'badge-gray', icon: 'ri-shield-check-line' },
}

// "Despachado" (se le pasó a la transportadora) no describe bien un pedido que el cliente recoge
// en tienda - ahí el hito real es que ya se lo entregamos en mano. Mismo estado real (`entregado`)
// en los dos casos, solo cambia cómo se llama en pantalla según `metodo_envio`.
export function labelEstadoVenta(venta) {
  if (venta.estado === 'entregado') return venta.metodo_envio === 'recogida' ? 'Entregado en tienda' : 'Despachado'
  return ESTADOS_VENTA[venta.estado]?.label || venta.estado
}

// Un pedido cancelado o devuelto no es una venta: el stock volvió a Inventario y el pago quedó
// reversado. Todos los totales de dinero, pedidos y unidades (Dashboard, Ventas, Reportes) cuentan
// solo los pedidos que pasan este filtro; los conteos por estado siguen usando la lista completa.
export function cuentaComoVenta(venta) {
  return !['cancelado', 'devuelto'].includes(venta.estado)
}

export const useVentasStore = defineStore('ventas', () => {
  const ventas = ref([])
  const auth = useAuthStore()
  const { showToast } = useToast()
  // Mismo criterio que huboCargaPrevia en pqrs.js/cotizaciones.js.
  let huboCargaPrevia = false

  async function cargarVentas() {
    const { data } = await api.get('/ventas')
    if (!auth.isAdmin && auth.usuario) {
      const antes = contarCambiosNuevos(auth.usuario.id_usuario)
      ventas.value = data
      const despues = contarCambiosNuevos(auth.usuario.id_usuario)
      if (huboCargaPrevia && despues > antes) {
        showToast('Uno de tus pedidos cambió de estado — revísalo.', 'info')
      }
    } else {
      ventas.value = data
    }
    huboCargaPrevia = true
  }

  // `detalleVentas`/`pagos` planos se derivan de `ventas` (el backend ya los anida por venta) —
  // se mantienen como getters aparte porque AdminReportesView/DashboardView/AdminVentasView los
  // consultan así (mismo shape que tenían con MockData, para no tocar esas vistas más de lo
  // necesario).
  const detalleVentas = computed(() =>
    ventas.value.flatMap((v) => v.items.map((item) => ({ ...item, id_venta: v.id_venta })))
  )
  const pagos = computed(() =>
    ventas.value.flatMap((v) => v.pagos.map((p) => ({ ...p, id_venta: v.id_venta })))
  )

  // Persiste una venta real: la usa tanto el checkout (compra del cliente) como el anticipo de
  // una cotización aprobada y "Nueva venta" en el panel admin (pedido manual por teléfono/
  // WhatsApp). El backend crea `ventas`/`detalle_ventas`/`pagos`, resta el stock y registra el
  // movimiento 'venta' en Inventario — por eso al terminar se recarga también el inventario.
  async function crearVenta({ id_usuario, items, subtotal, total, metodo_pago, metodo_envio, id_cotizacion = null, notas_cliente = null }) {
    const { data } = await api.post('/ventas', {
      id_usuario,
      items: items.map((i) => ({ id_producto: i.id_producto, cantidad: i.cantidad, precio_venta: i.precio_venta })),
      subtotal,
      total,
      metodo_pago,
      metodo_envio,
      id_cotizacion,
      notas_cliente,
    })
    await cargarVentas()
    await useCatalogStore().cargarInventario()
    return data
  }

  function getPagosDeVenta(id_venta) {
    return ventas.value.find((v) => v.id_venta === id_venta)?.pagos || []
  }

  // Cambia el estado de un pedido a mano (no hay integración logística real, el admin dice qué
  // pasó). Si el nuevo estado es 'cancelado'/'devuelto' y todavía no lo estaba, el backend repone
  // el stock (movimiento 'devolucion' por cada producto) y marca el/los pagos como 'reversado'.
  async function actualizarEstadoVenta(id_venta, nuevoEstado, { numero_guia = null, transportadora = null, motivo = null } = {}) {
    const { data } = await api.patch(`/ventas/${id_venta}/estado`, { estado: nuevoEstado, numero_guia, transportadora, motivo })
    await cargarVentas()
    await useCatalogStore().cargarInventario()
    return data
  }

  async function actualizarNotasVenta(id_venta, { notas_internas, fecha_entrega_estimada } = {}) {
    const { data } = await api.put(`/ventas/${id_venta}/notas`, { notas_internas, fecha_entrega_estimada })
    await cargarVentas()
    return data
  }

  // ── Aviso de "cambió de estado" para el cliente ─────────────────────────────
  // Mismo patrón que pqrs.js/cotizaciones.js. `ventas.value` para un cliente ya viene filtrado
  // por el backend (solo las suyas), así que acá no hace falta un getPorUsuario aparte - el
  // id_usuario solo se usa como llave del localStorage, no para filtrar datos.
  // Estados "notify-worthy": los únicos 3 que algún flujo real del sitio de verdad asigna hoy
  // (ver comentario de ESTADOS_VENTA arriba) - confirmado/preparando/despacho/enviado/garantia
  // existen en el ENUM pero ningún botón del admin los pone todavía.
  const ESTADOS_NOTIFICABLES = ['entregado', 'cancelado', 'devuelto']
  const CLAVE_VISTOS = 'acabados1a_ventas_vistos'
  function cargarVistos() {
    try { return JSON.parse(localStorage.getItem(CLAVE_VISTOS) || '{}') } catch { return {} }
  }
  const vistosPorUsuario = ref(cargarVistos())

  function ventasConCambioNuevo(id_usuario) {
    const vistos = vistosPorUsuario.value[id_usuario] || []
    return ventas.value.filter((v) => ESTADOS_NOTIFICABLES.includes(v.estado) && !vistos.includes(v.id_venta))
  }
  function contarCambiosNuevos(id_usuario) {
    return id_usuario ? ventasConCambioNuevo(id_usuario).length : 0
  }
  function marcarVentasVistas(id_usuario) {
    const idsConCambio = ventas.value.filter((v) => ESTADOS_NOTIFICABLES.includes(v.estado)).map((v) => v.id_venta)
    vistosPorUsuario.value = { ...vistosPorUsuario.value, [id_usuario]: idsConCambio }
    localStorage.setItem(CLAVE_VISTOS, JSON.stringify(vistosPorUsuario.value))
  }

  return {
    ventas,
    detalleVentas,
    pagos,
    cargarVentas,
    crearVenta,
    getPagosDeVenta,
    actualizarEstadoVenta,
    actualizarNotasVenta,
    contarCambiosNuevos,
    marcarVentasVistas,
  }
})
