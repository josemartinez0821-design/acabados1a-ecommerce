<script setup>
// RF19 - reportes gráficos de ventas, productos más vendidos y promociones -> vista
// `vista_productos_mas_vendidos`. Datos reales del backend (stores/ventas.js -> GET /api/ventas y
// stores/catalog.js). Exportación real: PNG/PDF son una captura visual del reporte (html2canvas + jsPDF, ver
// useReporteExport.js) y Word arma un documento de datos editable (docx), sin la gráfica.
import { ref, computed } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useVentasStore, cuentaComoVenta } from '../../stores/ventas'
import { formatCOP, formatDate, fechaLocalISO } from '../../composables/useFormat'
import { useReporteExport } from '../../composables/useReporteExport'
import { useToast } from '../../composables/useToast'

const catalog = useCatalogStore()
const ventasStore = useVentasStore()
const { showToast } = useToast()
const { exportarPNG, exportarPDF, exportarWord } = useReporteExport()

// Las ventas sembradas están en abril/mayo 2026, no cerca de "hoy" real — igual que
// DashboardView.vue, el rango por defecto se ancla a la venta más reciente, no a new Date().
const fechaReferencia = computed(() => {
  if (!ventasStore.ventas.length) return new Date()
  const maxTime = Math.max(...ventasStore.ventas.map((v) => new Date(v.fecha.replace(' ', 'T')).getTime()))
  return new Date(maxTime)
})
const fechaMinima = computed(() => {
  if (!ventasStore.ventas.length) return fechaReferencia.value
  const minTime = Math.min(...ventasStore.ventas.map((v) => new Date(v.fecha.replace(' ', 'T')).getTime()))
  return new Date(minTime)
})
function aISO(d) { return fechaLocalISO(d) }

// ── Filtro de rango de fechas ─────────────────────────────────
const PRESETS = [
  { value: 'todo', label: 'Todo el tiempo' },
  { value: '7d', label: 'Últimos 7 días' },
  { value: '30d', label: 'Últimos 30 días' },
  { value: 'mes', label: 'Este mes' },
  { value: 'personalizado', label: 'Rango personalizado' },
]
const preset = ref('todo')
const fechaInicioCustom = ref('')
const fechaFinCustom = ref('')

// Por defecto "todo el tiempo": así el ranking de productos más vendidos coincide de entrada con
// el preview del Dashboard (que tampoco filtra por fecha) — el link "Ver reportes" no debería
// aterrizar en números distintos a los que el admin ya vio ahí.
const rango = computed(() => {
  const ref = fechaReferencia.value
  if (preset.value === '7d') {
    const inicio = new Date(ref)
    inicio.setDate(inicio.getDate() - 6)
    return { inicio: aISO(inicio), fin: aISO(ref) }
  }
  if (preset.value === '30d') {
    const inicio = new Date(ref)
    inicio.setDate(inicio.getDate() - 29)
    return { inicio: aISO(inicio), fin: aISO(ref) }
  }
  if (preset.value === 'mes') {
    return { inicio: aISO(new Date(ref.getFullYear(), ref.getMonth(), 1)), fin: aISO(ref) }
  }
  if (preset.value === 'personalizado' && fechaInicioCustom.value && fechaFinCustom.value) {
    return { inicio: fechaInicioCustom.value, fin: fechaFinCustom.value }
  }
  return { inicio: aISO(fechaMinima.value), fin: aISO(ref) }
})
const rangoTexto = computed(() => `${formatDate(rango.value.inicio)} — ${formatDate(rango.value.fin)}`)

function enRango(fechaStr) {
  const dia = fechaStr.slice(0, 10)
  return dia >= rango.value.inicio && dia <= rango.value.fin
}

// ── Ventas y detalle filtrados por el rango activo ────────────
// Solo ventas efectivas: los pedidos cancelados o devueltos no suman dinero, pedidos ni unidades.
const ventasFiltradas = computed(() => ventasStore.ventas.filter((v) => cuentaComoVenta(v) && enRango(v.fecha)))
const idsVentasFiltradas = computed(() => new Set(ventasFiltradas.value.map((v) => v.id_venta)))
const detalleFiltrado = computed(() => ventasStore.detalleVentas.filter((d) => idsVentasFiltradas.value.has(d.id_venta)))

// ── Stat cards — tipografía plana sin chips de color, mismo criterio que Dashboard/Productos ──
const totalVentasPeriodo = computed(() => ventasFiltradas.value.reduce((s, v) => s + v.total, 0))
const totalPedidosPeriodo = computed(() => ventasFiltradas.value.length)
const ticketPromedio = computed(() => (totalPedidosPeriodo.value ? Math.round(totalVentasPeriodo.value / totalPedidosPeriodo.value) : 0))
const unidadesVendidas = computed(() => detalleFiltrado.value.reduce((s, d) => s + d.cantidad, 0))

// ── Gráfica de ventas del periodo — mismo patrón SVG (curva suave, tooltip, eje Y) que
// DashboardView.vue, adaptado a un rango de días variable en vez de una ventana fija de 30/6. ──
const CHART_W = 600
const CHART_H = 200
const PAD_LEFT = 52
const PAD_RIGHT = 12
const PAD_TOP = 14
const PAD_BOTTOM = 14
const CHART_PRIMARY = '#C0392B'

function formatEjeY(valor) {
  if (valor >= 1000000) return `$${(valor / 1000000).toFixed(1).replace('.0', '')}M`
  if (valor >= 1000) return `$${Math.round(valor / 1000)}k`
  return `$${valor}`
}

// Un solo control de tiempo, no dos: el admin solo elige el periodo arriba (7 días/30 días/mes/
// todo/personalizado) y la gráfica decide sola cómo agruparlo — por día si el rango es corto y
// legible, por mes si es tan amplio que un punto por día se vería enredado. Nada que el admin
// tenga que entender aparte.
const UMBRAL_DIAS_PARA_AGRUPAR_POR_MES = 31
const modoGrafica = computed(() => (diasVentana.value.length > UMBRAL_DIAS_PARA_AGRUPAR_POR_MES ? 'mes' : 'dia'))
const NOMBRES_MESES = ['Ene', 'Feb', 'Mar', 'Abr', 'May', 'Jun', 'Jul', 'Ago', 'Sep', 'Oct', 'Nov', 'Dic']

const diasVentana = computed(() => {
  const dias = []
  const cursor = new Date(`${rango.value.inicio}T00:00:00`)
  const fin = new Date(`${rango.value.fin}T00:00:00`)
  while (cursor <= fin) {
    dias.push(aISO(cursor))
    cursor.setDate(cursor.getDate() + 1)
  }
  return dias.length ? dias : [rango.value.fin]
})
const totalesPorDia = computed(() =>
  diasVentana.value.map((dia) => ventasFiltradas.value.filter((v) => v.fecha.startsWith(dia)).reduce((s, v) => s + v.total, 0))
)

const mesesVentana = computed(() => {
  const inicio = new Date(`${rango.value.inicio}T00:00:00`)
  const fin = new Date(`${rango.value.fin}T00:00:00`)
  const meses = []
  const cursor = new Date(inicio.getFullYear(), inicio.getMonth(), 1)
  const finMes = new Date(fin.getFullYear(), fin.getMonth(), 1)
  while (cursor <= finMes) {
    meses.push({ label: NOMBRES_MESES[cursor.getMonth()], anio: cursor.getFullYear(), mes: cursor.getMonth() })
    cursor.setMonth(cursor.getMonth() + 1)
  }
  return meses.length ? meses : [{ label: NOMBRES_MESES[fin.getMonth()], anio: fin.getFullYear(), mes: fin.getMonth() }]
})
const totalesPorMes = computed(() =>
  mesesVentana.value.map(({ anio, mes }) =>
    ventasFiltradas.value
      .filter((v) => {
        const d = new Date(v.fecha.replace(' ', 'T'))
        return d.getFullYear() === anio && d.getMonth() === mes
      })
      .reduce((s, v) => s + v.total, 0)
  )
)

const valoresGrafica = computed(() => (modoGrafica.value === 'mes' ? totalesPorMes.value : totalesPorDia.value))
const maxValor = computed(() => Math.max(...valoresGrafica.value, 1))

const NUM_LINEAS_Y = 4
const lineasY = computed(() => {
  const lineas = []
  for (let i = 0; i <= NUM_LINEAS_Y; i++) {
    const frac = i / NUM_LINEAS_Y
    lineas.push({ y: PAD_TOP + frac * (CHART_H - PAD_TOP - PAD_BOTTOM), valor: maxValor.value * (1 - frac) })
  }
  return lineas
})

const chartPuntos = computed(() => {
  const vals = valoresGrafica.value
  const max = maxValor.value
  return vals.map((v, i) => ({
    x: PAD_LEFT + (i * (CHART_W - PAD_LEFT - PAD_RIGHT)) / Math.max(vals.length - 1, 1),
    y: CHART_H - PAD_BOTTOM - (v / max) * (CHART_H - PAD_TOP - PAD_BOTTOM),
    valor: v,
  }))
})
function curvaSegmentos(pts) {
  let d = ''
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] || pts[i]
    const p1 = pts[i]
    const p2 = pts[i + 1]
    const p3 = pts[i + 2] || p2
    const cp1x = p1.x + (p2.x - p0.x) / 6
    const cp1y = p1.y + (p2.y - p0.y) / 6
    const cp2x = p2.x - (p3.x - p1.x) / 6
    const cp2y = p2.y - (p3.y - p1.y) / 6
    d += ` C${cp1x.toFixed(1)},${cp1y.toFixed(1)} ${cp2x.toFixed(1)},${cp2y.toFixed(1)} ${p2.x.toFixed(1)},${p2.y.toFixed(1)}`
  }
  return d
}
const lineaPath = computed(() => {
  const pts = chartPuntos.value
  return pts.length ? `M${pts[0].x},${pts[0].y}` + curvaSegmentos(pts) : ''
})
const areaPath = computed(() => {
  const pts = chartPuntos.value
  if (!pts.length) return ''
  const base = CHART_H - PAD_BOTTOM
  return `M${pts[0].x},${base} L${pts[0].x},${pts[0].y}` + curvaSegmentos(pts) + ` L${pts[pts.length - 1].x},${base} Z`
})

function etiquetaEje(i) {
  if (modoGrafica.value === 'mes') return mesesVentana.value[i].label
  return `${diasVentana.value[i].slice(8, 10)}/${diasVentana.value[i].slice(5, 7)}`
}
// En modo mes se muestran todas las etiquetas (pocos puntos); en modo día, uno de cada tantos
// para que no se amontonen, igual que Dashboard con su ventana fija de 30 días.
const pasoEtiquetas = computed(() => Math.max(1, Math.ceil(diasVentana.value.length / 10)))
function mostrarEtiqueta(i) {
  if (modoGrafica.value === 'mes') return true
  return i % pasoEtiquetas.value === 0 || i === diasVentana.value.length - 1
}
function etiquetaTooltip(i) {
  if (modoGrafica.value === 'mes') return `${mesesVentana.value[i].label} ${mesesVentana.value[i].anio}`
  return formatDate(diasVentana.value[i])
}

const hoverIndex = ref(null)
function onChartMove(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  const xSvg = ((e.clientX - rect.left) / rect.width) * CHART_W
  let cercano = 0
  let distMin = Infinity
  chartPuntos.value.forEach((p, i) => {
    const dist = Math.abs(p.x - xSvg)
    if (dist < distMin) { distMin = dist; cercano = i }
  })
  hoverIndex.value = cercano
}
function onChartLeave() { hoverIndex.value = null }

// ── Ventas por categoría — mismo patrón visual admin-bar-list del panel "Catálogo por categoría"
// de AdminProductosView.vue, pero con ingresos del periodo en vez de conteo de stock. ──
const ventasPorCategoria = computed(() => {
  const totales = {}
  detalleFiltrado.value.forEach((d) => {
    const producto = catalog.getProductById(d.id_producto)
    if (!producto) return
    totales[producto.id_categoria] = (totales[producto.id_categoria] || 0) + d.subtotal
  })
  const max = Math.max(...Object.values(totales), 1)
  return catalog.categorias
    .map((c) => ({
      id_categoria: c.id_categoria,
      nombre: c.nombre,
      ingresos: totales[c.id_categoria] || 0,
      pct: Math.round(((totales[c.id_categoria] || 0) / max) * 100),
    }))
    .filter((c) => c.ingresos > 0)
    .sort((a, b) => b.ingresos - a.ingresos)
})

// ── Productos más vendidos — tabla completa (misma fuente que el preview del Dashboard, sin
// recortar a 5, respetando el rango de fechas activo) ──
const productosMasVendidos = computed(() => {
  const totales = {}
  detalleFiltrado.value.forEach((d) => {
    if (!totales[d.id_producto]) totales[d.id_producto] = { cantidad: 0, ingresos: 0 }
    totales[d.id_producto].cantidad += d.cantidad
    totales[d.id_producto].ingresos += d.subtotal
  })
  return Object.entries(totales)
    .map(([id, t]) => ({ producto: catalog.getProductById(Number(id)), ...t }))
    .filter((x) => x.producto)
    .sort((a, b) => b.cantidad - a.cantidad)
})

// ── Promociones — mismo cálculo de vigencia que ya usa el sitio público
// (catalog.esPromoVigente: activo + dentro de fecha_inicio/fecha_fin), para que este reporte no
// contradiga lo que un cliente realmente ve. "Programada"/"Vencida" son estados calculados aquí,
// igual que "Vencida" en Cotizaciones — no existen como tal en el ENUM real de la BD. ──
function estadoPromo(promo) {
  if (!promo.activo) return { label: 'Inactiva', clase: 'badge-gray' }
  const hoy = fechaLocalISO()
  if (promo.fecha_inicio && hoy < promo.fecha_inicio) return { label: 'Programada', clase: 'badge-blue' }
  if (promo.fecha_fin && hoy > promo.fecha_fin) return { label: 'Vencida', clase: 'badge-gray' }
  return { label: 'Vigente', clase: 'badge-green' }
}
function aplicaA(promo) {
  // `productos` es un arreglo (un combo puede traer varios, ej. Estuco + Rodillo + Brocha).
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
const TIPO_PROMO_LABEL = { descuento: 'Descuento', combo: 'Combo', servicio: 'Servicio' }
const promocionesResumen = computed(() =>
  catalog.promociones.map((p) => ({
    ...p,
    aplicaA: aplicaA(p),
    valor: valorPromo(p),
    vigencia: `${formatDate(p.fecha_inicio)} – ${formatDate(p.fecha_fin)}`,
    tipoLabel: TIPO_PROMO_LABEL[p.tipo] || p.tipo,
    estado: estadoPromo(p),
  }))
)

// ── Exportación ────────────────────────────────────────────────
const reporteRef = ref(null)
const exportando = ref(null) // 'png' | 'pdf' | 'word' | null
const nombreArchivo = computed(() => `reporte-acabados1a_${rango.value.inicio}_${rango.value.fin}`)

async function onExportar(tipo) {
  exportando.value = tipo
  try {
    if (tipo === 'png') {
      await exportarPNG(reporteRef.value, nombreArchivo.value)
    } else if (tipo === 'pdf') {
      await exportarPDF(reporteRef.value, nombreArchivo.value)
    } else if (tipo === 'word') {
      await exportarWord(
        {
          titulo: 'Reporte — Acabados y Diseños 1A',
          rangoTexto: `Periodo: ${rangoTexto.value}`,
          stats: [
            { label: 'Ventas totales', valor: formatCOP(totalVentasPeriodo.value) },
            { label: 'Pedidos', valor: totalPedidosPeriodo.value },
            { label: 'Venta promedio', valor: formatCOP(ticketPromedio.value) },
            { label: 'Unidades vendidas', valor: unidadesVendidas.value },
          ],
          productos: productosMasVendidos.value.map((p) => ({
            nombre: p.producto.nombre,
            categoria: catalog.getCategoryName(p.producto.id_categoria),
            unidades: p.cantidad,
            ingresos: formatCOP(p.ingresos),
          })),
          promociones: promocionesResumen.value.map((p) => ({
            titulo: p.titulo,
            tipo: p.tipoLabel,
            vigencia: p.vigencia,
            estado: p.estado.label,
          })),
        },
        nombreArchivo.value
      )
    }
    showToast('Reporte exportado correctamente.', 'success')
  } catch (e) {
    showToast('No se pudo generar el archivo. Intenta de nuevo.', 'danger')
  } finally {
    exportando.value = null
  }
}
</script>

<template>
  <div class="admin-page-header">
    <div>
      <h1>Reportes</h1>
      <p>Gráficas de ventas, productos más vendidos y promociones — exporta el reporte completo en PNG, PDF o Word.</p>
    </div>
    <div class="rep-export-actions">
      <button type="button" class="btn btn-outline-red btn-sm" :disabled="!!exportando" @click="onExportar('png')">
        <i class="ri-image-line"></i> {{ exportando === 'png' ? 'Generando...' : 'PNG' }}
      </button>
      <button type="button" class="btn btn-outline-red btn-sm" :disabled="!!exportando" @click="onExportar('pdf')">
        <i class="ri-file-pdf-line"></i> {{ exportando === 'pdf' ? 'Generando...' : 'PDF' }}
      </button>
      <button type="button" class="btn btn-primary btn-sm" :disabled="!!exportando" @click="onExportar('word')">
        <i class="ri-file-word-line"></i> {{ exportando === 'word' ? 'Generando...' : 'Word' }}
      </button>
    </div>
  </div>

  <!-- FILTRO DE FECHA -->
  <div class="admin-filters-bar rep-filters">
    <div class="rep-presets">
      <button
        v-for="p in PRESETS"
        :key="p.value"
        type="button"
        class="rep-preset-btn"
        :class="{ active: preset === p.value }"
        @click="preset = p.value"
      >{{ p.label }}</button>
    </div>
    <div v-if="preset === 'personalizado'" class="rep-fechas-custom">
      <input v-model="fechaInicioCustom" type="date" class="form-control" />
      <span>a</span>
      <input v-model="fechaFinCustom" type="date" class="form-control" />
    </div>
    <span class="rep-rango-texto"><i class="ri-calendar-line"></i> {{ rangoTexto }}</span>
  </div>

  <div ref="reporteRef" class="rep-contenido">
    <!-- STAT CARDS -->
    <div class="rep-stats-grid">
      <div class="rep-stat">
        <div class="rep-stat-label"><i class="ri-bank-card-2-line"></i> Ventas totales</div>
        <strong class="rep-stat-value">{{ formatCOP(totalVentasPeriodo) }}</strong>
      </div>
      <div class="rep-stat">
        <div class="rep-stat-label"><i class="ri-shopping-cart-2-line"></i> Pedidos</div>
        <strong class="rep-stat-value">{{ totalPedidosPeriodo }}</strong>
      </div>
      <div class="rep-stat">
        <div class="rep-stat-label"><i class="ri-price-tag-3-line"></i> Venta promedio</div>
        <strong class="rep-stat-value">{{ formatCOP(ticketPromedio) }}</strong>
      </div>
      <div class="rep-stat">
        <div class="rep-stat-label"><i class="ri-archive-2-line"></i> Unidades vendidas</div>
        <strong class="rep-stat-value">{{ unidadesVendidas }}</strong>
      </div>
    </div>

    <!-- GRÁFICA + VENTAS POR CATEGORÍA -->
    <div class="admin-bottom-grid rep-grid-chart">
      <div class="admin-card">
        <div class="admin-card-header">
          <h2>Ventas del periodo</h2>
          <span>{{ formatCOP(totalVentasPeriodo) }}</span>
        </div>
        <div class="rep-chart-body">
          <div v-if="!ventasFiltradas.length" class="admin-empty">
            <i class="ri-line-chart-line"></i>
            <p>No hay ventas registradas en este rango.</p>
          </div>
          <template v-else>
            <div class="rep-chart-wrap">
              <svg :viewBox="`0 0 ${CHART_W} ${CHART_H}`" class="rep-chart">
                <g v-for="(linea, i) in lineasY" :key="i">
                  <line :x1="PAD_LEFT" :y1="linea.y" :x2="CHART_W - PAD_RIGHT" :y2="linea.y" stroke="#EDEDED" :stroke-dasharray="i === NUM_LINEAS_Y ? '0' : '4 4'" />
                  <text :x="PAD_LEFT - 10" :y="linea.y + 3" text-anchor="end" class="rep-chart-eje-y">{{ formatEjeY(linea.valor) }}</text>
                </g>

                <path :d="areaPath" fill="url(#repGrad)" />
                <path :d="lineaPath" fill="none" :stroke="CHART_PRIMARY" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />

                <g v-if="modoGrafica === 'mes'">
                  <circle v-for="(p, i) in chartPuntos" :key="i" :cx="p.x" :cy="p.y" r="4" fill="white" :stroke="CHART_PRIMARY" stroke-width="2.5" />
                </g>

                <g v-if="hoverIndex !== null">
                  <line :x1="chartPuntos[hoverIndex].x" :y1="PAD_TOP" :x2="chartPuntos[hoverIndex].x" :y2="CHART_H - PAD_BOTTOM" stroke="#C0392B" stroke-width="1" stroke-dasharray="3 3" opacity="0.4" />
                  <circle :cx="chartPuntos[hoverIndex].x" :cy="chartPuntos[hoverIndex].y" r="6" :fill="CHART_PRIMARY" stroke="white" stroke-width="2.5" />
                </g>

                <defs>
                  <linearGradient id="repGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" :stop-color="CHART_PRIMARY" stop-opacity="0.22" />
                    <stop offset="100%" :stop-color="CHART_PRIMARY" stop-opacity="0" />
                  </linearGradient>
                </defs>

                <rect x="0" y="0" :width="CHART_W" :height="CHART_H" fill="transparent" @mousemove="onChartMove" @mouseleave="onChartLeave" />
              </svg>

              <div
                v-if="hoverIndex !== null"
                class="rep-chart-tooltip"
                :style="{ left: (chartPuntos[hoverIndex].x / CHART_W) * 100 + '%', top: (chartPuntos[hoverIndex].y / CHART_H) * 100 + '%' }"
              >
                <strong>{{ formatCOP(chartPuntos[hoverIndex].valor) }}</strong>
                <span>{{ etiquetaTooltip(hoverIndex) }}</span>
              </div>
            </div>

            <div class="rep-chart-labels" :style="{ paddingLeft: (PAD_LEFT / CHART_W) * 100 + '%', paddingRight: (PAD_RIGHT / CHART_W) * 100 + '%' }">
              <span v-for="(v, i) in valoresGrafica" :key="i" :class="{ invisible: !mostrarEtiqueta(i) }">{{ etiquetaEje(i) }}</span>
            </div>
          </template>
        </div>
      </div>

      <div class="admin-card">
        <div class="admin-card-header"><h2>Ventas por categoría</h2></div>
        <div v-if="!ventasPorCategoria.length" class="admin-empty">
          <i class="ri-pie-chart-line"></i>
          <p>Sin ventas en este rango.</p>
        </div>
        <div v-else class="admin-bar-list">
          <div v-for="c in ventasPorCategoria" :key="c.id_categoria">
            <div class="admin-bar-row-top">
              <strong>{{ c.nombre }}</strong>
              <span>{{ formatCOP(c.ingresos) }}</span>
            </div>
            <div class="admin-bar-track"><div class="admin-bar-fill" :style="{ width: c.pct + '%' }"></div></div>
          </div>
        </div>
      </div>
    </div>

    <!-- PRODUCTOS MÁS VENDIDOS -->
    <div class="admin-card">
      <div class="admin-card-header">
        <h2>Productos más vendidos</h2>
        <span>{{ productosMasVendidos.length }} productos con ventas en el periodo</span>
      </div>
      <div v-if="!productosMasVendidos.length" class="admin-empty">
        <i class="ri-inbox-line"></i>
        <p>No hay ventas registradas en este rango.</p>
      </div>
      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Producto</th>
              <th>Categoría</th>
              <th>Unidades</th>
              <th>Ingresos</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(p, i) in productosMasVendidos" :key="p.producto.id_producto">
              <td>{{ i + 1 }}</td>
              <td>
                <div class="admin-prod-cell">
                  <img :src="p.producto.imagen_url" :alt="p.producto.nombre" class="admin-prod-thumb" />
                  <div class="admin-prod-info"><strong :title="p.producto.nombre">{{ p.producto.nombre }}</strong></div>
                </div>
              </td>
              <td>{{ catalog.getCategoryName(p.producto.id_categoria) }}</td>
              <td>{{ p.cantidad }} und</td>
              <td>{{ formatCOP(p.ingresos) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- PROMOCIONES -->
    <div class="admin-card">
      <div class="admin-card-header">
        <h2>Promociones</h2>
        <span>{{ promocionesResumen.length }} registradas</span>
      </div>
      <div v-if="!promocionesResumen.length" class="admin-empty">
        <i class="ri-price-tag-3-line"></i>
        <p>No hay promociones creadas.</p>
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
              <th>Estado</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in promocionesResumen" :key="p.id_promocion">
              <td>{{ p.titulo }}</td>
              <td>{{ p.tipoLabel }}</td>
              <td>{{ p.aplicaA }}</td>
              <td>{{ p.valor }}</td>
              <td>{{ p.vigencia }}</td>
              <td><span class="badge" :class="p.estado.clase">{{ p.estado.label }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rep-export-actions { display: flex; gap: 10px; flex-wrap: wrap; }

.rep-filters { justify-content: space-between; }
.rep-presets { display: flex; gap: 6px; flex-wrap: wrap; }
.rep-preset-btn {
  border: 1.5px solid var(--border); background: white; border-radius: 20px; padding: 7px 14px;
  font-family: var(--font-main); font-weight: 600; font-size: 0.78rem; color: var(--text-light);
  cursor: pointer; transition: var(--transition); white-space: nowrap;
}
.rep-preset-btn:hover { border-color: var(--primary); color: var(--primary); }
.rep-preset-btn.active { background: var(--secondary); border-color: var(--secondary); color: white; }
.rep-fechas-custom { display: flex; align-items: center; gap: 8px; }
.rep-fechas-custom span { font-size: 0.8rem; color: var(--text-muted); }
.rep-fechas-custom input { padding: 8px 10px; font-size: 0.82rem; }
.rep-rango-texto { display: flex; align-items: center; gap: 6px; font-size: 0.82rem; color: var(--text-muted); font-weight: 600; white-space: nowrap; }

/* Stat cards sin chip de color — mismo criterio tipográfico que .dash-stat/.prod-stat: la
   jerarquía la da el tamaño/peso de la fuente, el ícono es solo una referencia discreta. */
.rep-stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 18px; margin-bottom: 22px; }
.rep-stat { background: white; border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 20px 22px; }
.rep-stat-label {
  display: flex; align-items: center; gap: 7px; font-family: var(--font-main); font-weight: 700;
  font-size: 0.76rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.4px; margin-bottom: 12px;
}
.rep-stat-label i { font-size: 0.95rem; color: var(--text-light); }
.rep-stat-value { font-family: var(--font-main); font-weight: 800; font-size: 1.5rem; color: var(--secondary); }

.rep-grid-chart { align-items: stretch; }
.rep-chart-body { padding: 20px; }
.rep-chart-body .admin-empty { padding: 30px 20px; }


/* Gráfica SVG — mismas reglas que .dash-chart* de DashboardView.vue, copiadas con su propio
   nombre porque los estilos scoped de Vue no se heredan entre componentes (ver nota en
   project_acabados_1a.md: "Scoped vs global CSS"). Sin esto los <span> con .invisible no se
   ocultaban y las 21 etiquetas de fecha se veían todas pegadas una encima de otra. */
.rep-chart-wrap { position: relative; }
.rep-chart { width: 100%; height: auto; display: block; cursor: crosshair; }
.rep-chart-eje-y { font-size: 9.5px; fill: var(--text-muted); font-family: var(--font-body); }
.rep-chart-tooltip {
  position: absolute; transform: translate(-50%, -115%); background: var(--secondary); color: white;
  border-radius: var(--radius-sm); padding: 8px 12px; font-size: 0.76rem; text-align: center;
  pointer-events: none; white-space: nowrap; box-shadow: var(--shadow-lg); z-index: 5;
}
.rep-chart-tooltip strong { display: block; font-family: var(--font-main); font-weight: 800; font-size: 0.85rem; }
.rep-chart-tooltip span { color: rgba(255,255,255,0.65); }
.rep-chart-labels { display: flex; justify-content: space-between; margin-top: 8px; padding: 0 14px; }
.rep-chart-labels span { font-size: 0.74rem; color: var(--text-muted); font-weight: 600; white-space: nowrap; }
.rep-chart-labels span.invisible { visibility: hidden; }

@media (max-width: 1100px) {
  .rep-stats-grid { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 640px) {
  .rep-stats-grid { grid-template-columns: 1fr; }
  .rep-filters { flex-direction: column; align-items: stretch; }
}
</style>
