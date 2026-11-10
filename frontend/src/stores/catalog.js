import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { ServiciosEnriquecimiento } from '../data/serviciosEnriquecimiento'
import api from '../services/api'
import productoPlaceholder from '../assets/producto-placeholder.svg'
import { fechaLocalISO } from '../composables/useFormat'

// Las fotos de stock originales ya venían como URL completa (Unsplash) - pero las imágenes
// subidas de verdad por el admin (ProductoService.subirImagen) guardan una ruta relativa del
// backend ("/uploads/productos/..."), que un <img> interpretaría relativa al origen del
// FRONTEND (Vite), no del backend, donde el archivo realmente vive. Se resuelve una sola vez acá,
// al cargar los productos, en vez de en cada sitio del frontend que muestra una imagen.
const URL_BASE_BACKEND = import.meta.env.VITE_API_BASE_URL.replace(/\/api\/?$/, '')
function resolverImagenUrl(url) {
  if (!url) return productoPlaceholder // producto sin foto todavía — evita el <img> roto en catálogo/detalle
  if (url.startsWith('http://') || url.startsWith('https://')) return url
  return URL_BASE_BACKEND + url
}

// `categorias_productos` real no tiene columna `icono` (era mock-only, solo estas 5 categorías
// de negocio, prácticamente fijas) — se resuelve por nombre en vez de venir del backend.
const ICONOS_CATEGORIA = {
  'Pinturas y Vinilos': 'ri-paint-brush-line',
  'Materiales de Acabado': 'ri-brush-line',
  'Pegantes y Adhesivos': 'ri-drop-line',
  'Drywall y PVC': 'ri-layout-grid-line',
  'Herramientas': 'ri-hammer-line',
}

// TODO: cuando el backend Spring esté disponible, reemplazar las referencias
// a MockData por llamadas a `api` (src/services/api.js) manteniendo los mismos nombres de campo.
export const useCatalogStore = defineStore('catalog', () => {
  // Categorías ya viene del backend real (cargarCategorias(), llamado una vez al iniciar la app
  // en App.vue) — empieza vacío en vez de MockData.categorias.
  const categorias = ref([])
  async function cargarCategorias() {
    const { data } = await api.get('/categorias')
    categorias.value = data
  }
  function getCategoryIcon(nombre) {
    return ICONOS_CATEGORIA[nombre] || 'ri-price-tag-3-line'
  }

  // Productos/inventario ya vienen del backend real (cargarProductos()/cargarInventario(),
  // llamados una vez al iniciar la app en App.vue) — empiezan vacíos en vez de MockData.
  const productos = ref([])
  async function cargarProductos() {
    const { data } = await api.get('/productos')
    productos.value = data.map((p) => ({ ...p, imagen_url: resolverImagenUrl(p.imagen_url) }))
  }
  const inventario = ref([])
  async function cargarInventario() {
    const { data } = await api.get('/inventario')
    inventario.value = data
  }

  // Servicios ya viene del backend real (cargarServicios(), llamado una vez al iniciar la app en
  // App.vue) — empieza vacío en vez de MockData.servicios. La tabla real no tiene columnas para
  // rating/reseñas/galería/"qué incluye"/"cómo funciona" (siempre fueron mock-only) — se combinan
  // en el cliente con ServiciosEnriquecimiento por `codigo_servicio`, no editable desde el admin.
  const servicios = ref([])
  async function cargarServicios() {
    const { data } = await api.get('/servicios')
    servicios.value = data.map((s) => ({ ...s, ...(ServiciosEnriquecimiento[s.codigo_servicio] || {}) }))
  }
  const serviciosCatalogo = computed(() => servicios.value.filter((s) => s.activo))

  // Solo se usa en AdminInventarioView.vue - no se precarga en App.vue como productos/inventario,
  // se pide desde esa misma vista al montar.
  const movimientosInventario = ref([])
  async function cargarMovimientos() {
    const { data } = await api.get('/movimientos-inventario')
    movimientosInventario.value = data
  }
  // Promociones real (tabla propia + promocion_productos, agregadas 2026-08-20 ahora que sí se
  // puede tocar el esquema) — un combo puede traer varios productos, ya no uno solo.
  const promociones = ref([])
  async function cargarPromociones() {
    const { data } = await api.get('/promociones')
    promociones.value = data
  }
  // Tabla real sin datos sensibles (nombre/valor de cada tarifa) - antes se resolvía contra
  // MockData.impuestos, cuyos ids ya no coinciden con los reales (ver ImpuestoController.java).
  const impuestos = ref([])
  async function cargarImpuestos() {
    const { data } = await api.get('/impuestos')
    impuestos.value = data
  }

  // Datos de la empresa (teléfono, dirección, redes, misión/visión...) - ya vienen del backend
  // real (cargarConfiguracion(), llamado una vez al iniciar la app en App.vue) — empieza vacío
  // en vez de MockData.configuracion. La tabla `configuracion` es genérica clave/valor (la misma
  // que ya usaba grupos_variante_productos) - acá se aplanan todas sus filas en un solo objeto
  // {telefono, direccion, ...} para que el resto del frontend siga leyéndolo igual que antes.
  const configuracion = ref({})
  async function cargarConfiguracion() {
    const { data } = await api.get('/configuracion')
    configuracion.value = Object.fromEntries(data.map((c) => [c.clave, c.valor]))
  }

  // `productos` real no tiene columnas para agrupar tamaños/colores (grupo_variante, tamano,
  // orden_variante, mostrarEnCatalogo eran mock-only) — se resuelve leyendo la fila
  // `configuracion` con clave 'grupos_variante_productos' (ver [[project-promociones-combos-mapping]]
  // en la memoria del proyecto, mismo mecanismo pensado para promociones/combos).
  const gruposVariante = ref([])
  async function cargarGruposVariante() {
    try {
      const { data } = await api.get('/configuracion/grupos_variante_productos')
      gruposVariante.value = JSON.parse(data.valor)
    } catch (e) {
      gruposVariante.value = [] // todavía no existe esa fila de configuración
    }
  }

  const productosDestacados = computed(() => productos.value.filter((p) => p.activo && p.destacado))

  // Productos que son un tamaño/color "no representante" de su familia — se ocultan del catálogo
  // principal, igual que antes hacía mostrarEnCatalogo:false, pero leído desde gruposVariante.
  const idsOcultosPorVariante = computed(() => {
    const set = new Set()
    gruposVariante.value.forEach((g) => g.variantes.forEach((v) => { if (!v.representante) set.add(v.id_producto) }))
    return set
  })
  const productosCatalogo = computed(() => productos.value.filter((p) => p.activo && !idsOcultosPorVariante.value.has(p.id_producto)))

  function getVariantesTamano(producto) {
    if (!producto) return []
    const grupo = gruposVariante.value.find((g) => g.variantes.some((v) => v.id_producto === producto.id_producto))
    if (!grupo) return []
    return grupo.variantes
      .map((v) => {
        const p = productos.value.find((pp) => pp.id_producto === v.id_producto)
        return p ? { ...p, tamano: v.tamano, orden_variante: v.orden } : null
      })
      .filter(Boolean)
      .sort((a, b) => a.orden_variante - b.orden_variante)
  }

  function getCategoryName(id_categoria) {
    const cat = categorias.value.find((c) => c.id_categoria === id_categoria)
    return cat ? cat.nombre : 'Sin categoría'
  }

  function getCategoryCount(id_categoria) {
    return productosCatalogo.value.filter((p) => p.id_categoria === id_categoria).length
  }

  function getCategoryStockCount(id_categoria) {
    return productosCatalogo.value.filter((p) => p.id_categoria === id_categoria && getProductStock(p.id_producto) > 0).length
  }

  function getStockStatus(id_producto) {
    const inv = inventario.value.find((i) => i.id_producto === id_producto)
    if (!inv) return { label: 'Sin inventario', class: 'stock-out' }
    if (inv.cantidad_disponible === 0) return { label: 'Agotado', class: 'stock-out' }
    // stock_bajo lo calcula el backend: al público ya no le llega stock_minimo (hallazgo A-2).
    if (inv.stock_bajo) return { label: 'Stock bajo', class: 'stock-low' }
    return { label: 'Disponible', class: 'stock-in' }
  }

  function getProductStock(id_producto) {
    const inv = inventario.value.find((i) => i.id_producto === id_producto)
    return inv ? inv.cantidad_disponible : 0
  }

  // Registra un movimiento manual (entrada/salida/ajuste/devolución) desde el panel de Inventario.
  // Las ventas y sus cancelaciones/devoluciones NO pasan por aquí - el trigger real de la BD y
  // VentaService.reponerStock() ya se encargan de esos dos casos (ver stores/ventas.js), así que
  // `tipo_movimiento: 'venta'` ni siquiera es una opción válida en este endpoint.
  async function registrarMovimientoInventario({ id_producto, tipo_movimiento, cantidad, descripcion = '' }) {
    const { data } = await api.post('/movimientos-inventario', {
      id_producto, tipo_movimiento, cantidad, descripcion,
    })
    await cargarMovimientos()
    await cargarInventario()
    return data
  }

  function getMovimientosPorProducto(id_producto) {
    return movimientosInventario.value
      .filter((m) => m.id_producto === id_producto)
      .sort((a, b) => new Date(b.fecha.replace(' ', 'T')) - new Date(a.fecha.replace(' ', 'T')))
  }

  async function actualizarUmbralesStock(id_producto, { stock_minimo, stock_maximo, ubicacion_bodega } = {}) {
    const { data } = await api.put(`/inventario/${id_producto}/umbrales`, {
      stock_minimo, stock_maximo, ubicacion_bodega,
    })
    await cargarInventario()
    return data
  }

  function getProductById(id_producto) {
    return productos.value.find((p) => p.id_producto === Number(id_producto)) || null
  }

  function getServiceById(id_servicio) {
    return servicios.value.find((s) => s.id_servicio === Number(id_servicio)) || null
  }

  // ── CRUD de productos (panel admin) — llama al backend real y resincroniza desde ahí en vez
  // de parchar el array local a mano: así los campos que ahora calcula/completa el servidor
  // (codigo_producto autogenerado, los defaults de precio_compra/precio_mayorista/
  // descuento_maximo/id_impuesto) quedan reflejados sin tener que adivinarlos en el cliente.
  // El borrado es soft-delete en el backend (activo=false, no DELETE real — cotizacion_productos/
  // detalle_ventas tienen ON DELETE RESTRICT), por eso productosCatalogo/productosDestacados
  // arriba filtran por `activo`.
  async function crearProducto(datos) {
    // Devuelve el producto creado (antes se descartaba) - ProductoFormModal.vue necesita el
    // id_producto real para poder subirle la imagen justo después de crearlo.
    const { data } = await api.post('/productos', datos)
    await cargarProductos()
    await cargarInventario()
    return data
  }

  async function actualizarProducto(id_producto, datos) {
    await api.put(`/productos/${id_producto}`, datos)
    await cargarProductos()
    await cargarInventario()
  }

  // Sube el archivo real de la imagen (no una URL de texto) - ProductoService.subirImagen la
  // guarda como archivo en el backend, no en la base de datos.
  async function subirImagenProducto(id_producto, archivo) {
    const formData = new FormData()
    formData.append('archivo', archivo)
    await api.post(`/productos/${id_producto}/imagen`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    await cargarProductos()
  }

  async function eliminarProducto(id_producto) {
    await api.delete(`/productos/${id_producto}`)
    await cargarProductos()
    await cargarInventario()
  }

  // Solo estas categorías son fabricación propia (Vinilos, Estuco/Graniplast, Pegantes) — el resto
  // (Drywall/PVC, Herramientas) son productos de terceros que solo se revenden, así que no se
  // cotizan al por mayor. Compartido entre DetalleProductoView.vue y CotizacionesView.vue.
  // Por NOMBRE, no por id_categoria — el backend real no tiene por qué numerar las categorías
  // igual que el mock (ver memoria del proyecto: se renombraron/reordenaron el 2026-08-20).
  const NOMBRES_FABRICACION_PROPIA = ['Pinturas y Vinilos', 'Materiales de Acabado', 'Pegantes y Adhesivos']
  function esFabricacionPropia(producto) {
    return NOMBRES_FABRICACION_PROPIA.includes(getCategoryName(producto.id_categoria))
  }
  const productosFabricacionPropia = computed(() => productosCatalogo.value.filter(esFabricacionPropia))

  // Vigente = activa Y dentro de fecha_inicio/fecha_fin (si la promo los tiene — son opcionales,
  // una promo sin fecha de fin no vence). Antes solo se validaba `activo`, dejando pasar promos ya
  // vencidas o programadas a futuro en el sitio público.
  function esPromoVigente(promo) {
    if (!promo.activo) return false
    const hoy = fechaLocalISO()
    if (promo.fecha_inicio && hoy < promo.fecha_inicio) return false
    if (promo.fecha_fin && hoy > promo.fecha_fin) return false
    return true
  }

  function getActivePromoForProduct(id_producto) {
    // Solo promos tipo "descuento" representan un % o precio especial aplicable al precio de este
    // producto individual (los combos tienen su propio precio de paquete — ver getComboForProduct).
    // `productos` es un arreglo (un combo puede traer varios) — antes era un solo id_producto.
    return promociones.value.find((p) => esPromoVigente(p) && p.tipo === 'descuento' && p.productos?.includes(Number(id_producto))) || null
  }

  function getComboForProduct(id_producto) {
    return promociones.value.find((p) => esPromoVigente(p) && p.tipo === 'combo' && p.productos?.includes(Number(id_producto))) || null
  }

  // Para el slider "Promociones del Mes" del Home — antes mostraba TODAS las promociones sin
  // filtrar (ni por activo/fechas ni por relevancia), lo que sacaba promos vencidas o inactivas.
  // `destacado` es la curación manual del admin sobre cuáles van en ese espacio limitado.
  const promocionesDestacadas = computed(() => promociones.value.filter((p) => esPromoVigente(p) && p.destacado))

  // Recomendador servicios <-> productos: qué tipos de servicio tienen sentido según la
  // categoría del producto (ej. pinturas -> aplicación de pintura; drywall -> instalación de drywall).
  // Se usa en Detalle de producto, Carrito y Nueva Cotización para sugerir el servicio relacionado.
  const NOMBRE_A_TIPOS_SERVICIO = {
    'Pinturas y Vinilos': ['aplicacion_pintura', 'asesoria'],
    'Materiales de Acabado': ['aplicacion_pintura'], // estuco, graniplast -> misma bolsa que "aplicación de pintura"
    'Pegantes y Adhesivos': ['instalacion'],
    'Drywall y PVC': ['drywall', 'pvc', 'instalacion'],
    'Herramientas': [], // sin servicio directo asociado
  }

  function getServiciosSugeridos(idsCategorias, excluirIds = []) {
    const tipos = new Set()
    idsCategorias.forEach((catId) => {
      ;(NOMBRE_A_TIPOS_SERVICIO[getCategoryName(catId)] || []).forEach((t) => tipos.add(t))
    })
    if (!tipos.size) return []
    const excluir = new Set(excluirIds)
    // Se usa para recomendar en vivo en varias páginas públicas - filtra por activo (a diferencia
    // de getServiceById, que resuelve un id ya conocido y se deja sin filtrar a propósito).
    return serviciosCatalogo.value.filter((s) => tipos.has(s.tipo_servicio) && !excluir.has(s.id_servicio))
  }

  // ── CRUD de servicios (panel admin) — mismo patrón que crearProducto/actualizarProducto/
  // eliminarProducto: llama al backend real y resincroniza con cargarServicios() en vez de
  // parchar el array local. Borrado también es soft-delete (cotizacion_servicios tiene ON DELETE
  // RESTRICT contra servicios, mismo motivo que productos).
  async function crearServicio(datos) {
    await api.post('/servicios', datos)
    await cargarServicios()
  }

  async function actualizarServicio(id_servicio, datos) {
    await api.put(`/servicios/${id_servicio}`, datos)
    await cargarServicios()
  }

  async function eliminarServicio(id_servicio) {
    await api.delete(`/servicios/${id_servicio}`)
    await cargarServicios()
  }

  // ── CRUD de promociones (panel admin) — mismo patrón de resync que productos/servicios. El
  // borrado es hard-delete real (a diferencia de productos/servicios): promocion_productos tiene
  // ON DELETE CASCADE contra promociones y nada más referencia id_promocion (confirmado en
  // information_schema), así que no hace falta soft-delete aquí.
  async function crearPromocion(datos) {
    await api.post('/promociones', datos)
    await cargarPromociones()
  }

  async function actualizarPromocion(id_promocion, datos) {
    await api.put(`/promociones/${id_promocion}`, datos)
    await cargarPromociones()
  }

  async function eliminarPromocion(id_promocion) {
    await api.delete(`/promociones/${id_promocion}`)
    await cargarPromociones()
  }

  return {
    categorias,
    cargarCategorias,
    getCategoryIcon,
    productos,
    cargarProductos,
    servicios,
    cargarServicios,
    serviciosCatalogo,
    inventario,
    cargarInventario,
    gruposVariante,
    cargarGruposVariante,
    movimientosInventario,
    cargarMovimientos,
    impuestos,
    cargarImpuestos,
    promociones,
    cargarPromociones,
    promocionesDestacadas,
    esPromoVigente,
    getComboForProduct,
    configuracion,
    cargarConfiguracion,
    productosDestacados,
    productosCatalogo,
    productosFabricacionPropia,
    esFabricacionPropia,
    getVariantesTamano,
    getCategoryName,
    getCategoryCount,
    getCategoryStockCount,
    getStockStatus,
    getProductStock,
    getProductById,
    getServiceById,
    getActivePromoForProduct,
    getServiciosSugeridos,
    registrarMovimientoInventario,
    getMovimientosPorProducto,
    actualizarUmbralesStock,
    crearProducto,
    subirImagenProducto,
    actualizarProducto,
    eliminarProducto,
    crearServicio,
    actualizarServicio,
    eliminarServicio,
    crearPromocion,
    actualizarPromocion,
    eliminarPromocion,
  }
})
