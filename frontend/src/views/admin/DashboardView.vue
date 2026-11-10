<script setup>
// RF15 - panel de administración: métricas clave, ventas (día/mes), stock bajo, productos más
// vendidos, actividad reciente. Ventas/cotizaciones/PQRS ya vienen del backend real.
import { ref, computed } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { useCatalogStore } from '../../stores/catalog'
import { useVentasStore, cuentaComoVenta } from '../../stores/ventas'
import { useCotizacionesStore } from '../../stores/cotizaciones'
import { usePqrsStore } from '../../stores/pqrs'
import { formatCOP, formatDate, formatDateTime, fechaLocalISO } from '../../composables/useFormat'

const auth = useAuthStore()
const catalog = useCatalogStore()
const ventasStore = useVentasStore()
const cotizStore = useCotizacionesStore()
const pqrsStore = usePqrsStore()

const primerNombre = computed(() => auth.usuario?.nombre?.split(' ')[0] || '')
const hoyDate = new Date()
function capitalizar(t) { return t.charAt(0).toUpperCase() + t.slice(1) }
const fechaHoy = computed(() => capitalizar(hoyDate.toLocaleDateString('es-CO', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })))

// Saludo según la hora del día — más amigable que un genérico "Bienvenido de nuevo" siempre igual.
const saludoHora = computed(() => {
  const h = hoyDate.getHours()
  if (h < 12) return 'Buenos días'
  if (h < 19) return 'Buenas tardes'
  return 'Buenas noches'
})

function imprimir() {
  window.print()
}

// ── Métricas ─────────────────────────────────────────────────
// Las ventas de ejemplo están todas en abril/mayo de 2026 (no cerca de "hoy" real, que avanza cada
// día) — las ventanas de la gráfica se anclan a la venta más reciente en vez de a la fecha actual
// del sistema, para que siempre haya datos que mostrar en vez de una gráfica vacía.
const fechaReferencia = computed(() => {
  if (!ventasStore.ventas.length) return new Date()
  const maxTime = Math.max(...ventasStore.ventas.map((v) => new Date(v.fecha.replace(' ', 'T')).getTime()))
  return new Date(maxTime)
})

// Solo ventas efectivas: los pedidos cancelados o devueltos no suman ingresos ni pedidos.
const ventasEfectivas = computed(() => ventasStore.ventas.filter(cuentaComoVenta))
const totalVentas = computed(() => ventasEfectivas.value.reduce((sum, v) => sum + v.total, 0))
const totalPedidos = computed(() => ventasEfectivas.value.length)
const pqrsPendientes = computed(() => pqrsStore.pqrs.filter((p) => p.estado === 'abierto' || p.estado === 'en_proceso').length)

// ── Gráfica de ventas — por día (últimos 30) o por mes (últimos 6), con curva suave y tooltip ──
const CHART_W = 600
const CHART_H = 200
// Padding asimétrico: más espacio a la izquierda para las etiquetas del eje Y (valores en $),
// que la gráfica anterior no tenía — sin eso no había forma de leer una magnitud aproximada sin
// pasar el mouse por cada punto.
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

const modoGrafica = ref('mes') // 'dia' | 'mes'
const NOMBRES_MESES = ['Ene', 'Feb', 'Mar', 'Abr', 'May', 'Jun', 'Jul', 'Ago', 'Sep', 'Oct', 'Nov', 'Dic']

const mesesVentana = computed(() => {
  const ref = fechaReferencia.value
  const meses = []
  for (let i = 5; i >= 0; i--) {
    const d = new Date(ref.getFullYear(), ref.getMonth() - i, 1)
    meses.push({ label: NOMBRES_MESES[d.getMonth()], anio: d.getFullYear(), mes: d.getMonth() })
  }
  return meses
})
const totalesPorMes = computed(() =>
  mesesVentana.value.map(({ anio, mes }) =>
    ventasEfectivas.value
      .filter((v) => {
        const d = new Date(v.fecha.replace(' ', 'T'))
        return d.getFullYear() === anio && d.getMonth() === mes
      })
      .reduce((s, v) => s + v.total, 0)
  )
)
// Tendencia = variación del último mes con datos contra el anterior — igual que antes, solo que
// ahora lee de la ventana dinámica en vez de un array fijo "Ene-Jun".
const tendenciaVentas = computed(() => {
  const vals = totalesPorMes.value.filter((v) => v > 0)
  if (vals.length < 2) return null
  const anterior = vals[vals.length - 2]
  const actual = vals[vals.length - 1]
  if (!anterior) return null
  return Math.round(((actual - anterior) / anterior) * 100)
})

const diasVentana = computed(() => {
  const ref = fechaReferencia.value
  const dias = []
  for (let i = 29; i >= 0; i--) {
    const d = new Date(ref)
    d.setDate(d.getDate() - i)
    dias.push(fechaLocalISO(d))
  }
  return dias
})
const totalesPorDia = computed(() =>
  diasVentana.value.map((dia) => ventasEfectivas.value.filter((v) => v.fecha.startsWith(dia)).reduce((s, v) => s + v.total, 0))
)

const valoresGrafica = computed(() => (modoGrafica.value === 'mes' ? totalesPorMes.value : totalesPorDia.value))
const totalPeriodo = computed(() => valoresGrafica.value.reduce((s, v) => s + v, 0))

function etiquetaEje(i) {
  if (modoGrafica.value === 'mes') return mesesVentana.value[i].label
  return diasVentana.value[i].slice(8, 10)
}
// En modo día (30 puntos) mostrar una etiqueta cada 5 días para que no se amontonen; en modo mes
// (6 puntos) se muestran todas.
function mostrarEtiqueta(i) {
  if (modoGrafica.value === 'mes') return true
  return i % 5 === 0 || i === diasVentana.value.length - 1
}
function etiquetaTooltip(i) {
  if (modoGrafica.value === 'mes') return `${mesesVentana.value[i].label} ${mesesVentana.value[i].anio}`
  return formatDate(diasVentana.value[i])
}

const maxValor = computed(() => Math.max(...valoresGrafica.value, 1))

// Líneas de referencia del eje Y con su valor — de arriba (máximo) a abajo (cero), en vez de solo
// líneas punteadas sin ninguna cifra al lado.
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
    x: PAD_LEFT + (i * (CHART_W - PAD_LEFT - PAD_RIGHT)) / (vals.length - 1),
    y: CHART_H - PAD_BOTTOM - (v / max) * (CHART_H - PAD_TOP - PAD_BOTTOM),
    valor: v,
  }))
})
// Catmull-Rom → Bézier cúbica: da una curva suave entre los puntos en vez de segmentos rectos.
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

// Tooltip flotante al pasar el mouse — busca el punto más cercano a la posición X en vez de
// depender de acertarle a un círculo pequeño, así el hover se siente fluido en toda la gráfica.
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
function onChartLeave() {
  hoverIndex.value = null
}

// ── Stock bajo ───────────────────────────────────────────────
// "Crítico" = a punto de agotarse de verdad: muy pocas unidades en términos absolutos, o por
// debajo de la mitad del mínimo configurado — lo que dispare primero. Antes solo miraba el % del
// mínimo, así que un producto con un `stock_minimo` chico (ej. 5) nunca se veía "crítico" aunque
// quedaran solo 3 unidades reales — mismo umbral usado en AdminInventarioView.vue, no inventar otro.
const UMBRAL_CRITICO_ABSOLUTO = 4
function esCritico(item) {
  return item.cantidad_disponible <= UMBRAL_CRITICO_ABSOLUTO || item.cantidad_disponible <= item.stock_minimo * 0.5
}
const stockBajo = computed(() => {
  return catalog.inventario
    .filter((inv) => inv.cantidad_disponible <= inv.stock_minimo)
    .map((inv) => ({ ...inv, producto: catalog.getProductById(inv.id_producto) }))
    .filter((item) => item.producto)
    .sort((a, b) => {
      const critA = esCritico(a)
      const critB = esCritico(b)
      if (critA !== critB) return critA ? -1 : 1
      return a.cantidad_disponible / a.stock_minimo - b.cantidad_disponible / b.stock_minimo
    })
    .slice(0, 5)
})

// ── Productos más vendidos (real, desde detalle_ventas) ─────
const productosMasVendidos = computed(() => {
  const totales = {}
  const idsEfectivas = new Set(ventasEfectivas.value.map((v) => v.id_venta))
  ventasStore.detalleVentas.forEach((d) => {
    if (!idsEfectivas.has(d.id_venta)) return
    totales[d.id_producto] = (totales[d.id_producto] || 0) + d.cantidad
  })
  const max = Math.max(...Object.values(totales), 1)
  return Object.entries(totales)
    .map(([id, cantidad]) => ({ producto: catalog.getProductById(Number(id)), cantidad, pct: Math.round((cantidad / max) * 100) }))
    .filter((x) => x.producto)
    .sort((a, b) => b.cantidad - a.cantidad)
    .slice(0, 5)
})

// ── Actividad reciente (pedidos + cotizaciones, ordenados por fecha real) ──
const actividadReciente = computed(() => {
  const pedidos = ventasStore.ventas.map((v) => ({
    tipo: 'pedido',
    fecha: v.fecha,
    titulo: `Pedido ${v.numero_venta} — ${v.estado}`,
    detalle: `${v.usuario.nombre} ${v.usuario.apellido} · ${formatCOP(v.total)}`,
    icono: 'ri-shopping-bag-3-line',
    color: 'azul',
    etiqueta: 'PEDIDOS',
  }))
  const cotizaciones = cotizStore.cotizaciones.map((c) => ({
    tipo: 'cotizacion',
    fecha: c.fecha,
    titulo: `Cotización ${c.numero_cotizacion} — ${c.estado.replace('_', ' ')}`,
    detalle: `${c.usuario.nombre} ${c.usuario.apellido} · ${formatCOP(c.total_estimado)}`,
    icono: 'ri-file-list-3-line',
    color: 'verde',
    etiqueta: 'COTIZACIONES',
  }))
  return [...pedidos, ...cotizaciones]
    .sort((a, b) => new Date(b.fecha.replace(' ', 'T')) - new Date(a.fecha.replace(' ', 'T')))
    .slice(0, 6)
})

// `fecha` de ventas/cotizaciones siempre trae hora (DATETIME real) - formatDateTime() ya es seguro
// para los dos formatos que puede traer (con espacio o con "T"), su propio replace(' ', 'T') no
// hace nada si ya viene en ISO. El viejo `fecha.includes(' ')` fallaba con fechas reales (vienen
// con "T", no espacio) y cortaba a formatDate(), que no sabe leer una hora -> "Invalid Date".
function fechaActividad(fecha) {
  return formatDateTime(fecha)
}
</script>

<template>
  <div class="dash">
    <!-- HERO -->
    <div class="dash-hero">
      <div class="dash-hero-text">
        <p class="dash-hero-fecha"><i class="ri-calendar-line"></i> {{ fechaHoy }}</p>
        <h1>{{ saludoHora }}, {{ primerNombre }}</h1>
        <p class="dash-hero-sub">Así va la tienda hoy — aquí tienes el resumen completo.</p>
      </div>
      <div class="dash-hero-actions">
        <button type="button" class="btn btn-outline-red" @click="imprimir"><i class="ri-printer-line"></i> Imprimir</button>
        <RouterLink to="/admin/productos?nuevo=true" class="btn btn-primary"><i class="ri-add-line"></i> Nuevo Producto</RouterLink>
      </div>
    </div>

    <!-- MÉTRICAS -->
    <div class="dash-metricas">
      <RouterLink to="/admin/ventas" class="dash-stat">
        <div class="dash-stat-label"><i class="ri-bank-card-2-line"></i> Ventas totales</div>
        <div class="dash-stat-value-row">
          <strong class="dash-stat-value">{{ formatCOP(totalVentas) }}</strong>
          <span v-if="tendenciaVentas !== null" class="dash-stat-trend" :class="tendenciaVentas >= 0 ? 'up' : 'down'">
            <i :class="tendenciaVentas >= 0 ? 'ri-arrow-up-line' : 'ri-arrow-down-line'"></i>{{ Math.abs(tendenciaVentas) }}%
          </span>
        </div>
      </RouterLink>

      <RouterLink to="/admin/ventas" class="dash-stat">
        <div class="dash-stat-label"><i class="ri-shopping-cart-2-line"></i> Pedidos realizados</div>
        <div class="dash-stat-value-row">
          <strong class="dash-stat-value">{{ totalPedidos }}</strong>
        </div>
      </RouterLink>

      <RouterLink to="/admin/pqrs" class="dash-stat">
        <div class="dash-stat-label"><i class="ri-customer-service-2-line"></i> PQRS pendientes</div>
        <div class="dash-stat-value-row">
          <strong class="dash-stat-value" :class="{ alerta: pqrsPendientes }">{{ pqrsPendientes }}</strong>
          <span v-if="pqrsPendientes" class="dash-stat-note">requieren atención</span>
        </div>
      </RouterLink>
    </div>

    <!-- GRÁFICA + STOCK BAJO -->
    <div class="dash-grid">
      <div class="dash-panel">
        <div class="dash-panel-header">
          <div>
            <h2>{{ modoGrafica === 'mes' ? 'Ventas mensuales' : 'Ventas diarias' }}</h2>
            <div class="dash-chart-total">
              <strong>{{ formatCOP(totalPeriodo) }}</strong>
              <span v-if="tendenciaVentas !== null && modoGrafica === 'mes'" class="dash-metric-trend" :class="tendenciaVentas >= 0 ? 'up' : 'down'">
                <i :class="tendenciaVentas >= 0 ? 'ri-arrow-up-line' : 'ri-arrow-down-line'"></i> {{ Math.abs(tendenciaVentas) }}%
              </span>
            </div>
          </div>
          <div class="dash-toggle">
            <button type="button" :class="{ active: modoGrafica === 'dia' }" @click="modoGrafica = 'dia'">Por día</button>
            <button type="button" :class="{ active: modoGrafica === 'mes' }" @click="modoGrafica = 'mes'">Por mes</button>
          </div>
        </div>

        <div class="dash-chart-wrap">
          <svg :viewBox="`0 0 ${CHART_W} ${CHART_H}`" class="dash-chart">
            <g v-for="(linea, i) in lineasY" :key="i">
              <line :x1="PAD_LEFT" :y1="linea.y" :x2="CHART_W - PAD_RIGHT" :y2="linea.y" stroke="#EDEDED" :stroke-dasharray="i === NUM_LINEAS_Y ? '0' : '4 4'" />
              <text :x="PAD_LEFT - 10" :y="linea.y + 3" text-anchor="end" class="dash-chart-eje-y">{{ formatEjeY(linea.valor) }}</text>
            </g>

            <path :d="areaPath" fill="url(#dashGrad)" />
            <path :d="lineaPath" fill="none" :stroke="CHART_PRIMARY" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />

            <g v-if="modoGrafica === 'mes'">
              <circle v-for="(p, i) in chartPuntos" :key="i" :cx="p.x" :cy="p.y" r="4" fill="white" :stroke="CHART_PRIMARY" stroke-width="2.5" />
            </g>

            <g v-if="hoverIndex !== null">
              <line :x1="chartPuntos[hoverIndex].x" :y1="PAD_TOP" :x2="chartPuntos[hoverIndex].x" :y2="CHART_H - PAD_BOTTOM" stroke="#C0392B" stroke-width="1" stroke-dasharray="3 3" opacity="0.4" />
              <circle :cx="chartPuntos[hoverIndex].x" :cy="chartPuntos[hoverIndex].y" r="6" :fill="CHART_PRIMARY" stroke="white" stroke-width="2.5" />
            </g>

            <defs>
              <linearGradient id="dashGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" :stop-color="CHART_PRIMARY" stop-opacity="0.22" />
                <stop offset="100%" :stop-color="CHART_PRIMARY" stop-opacity="0" />
              </linearGradient>
            </defs>

            <rect x="0" y="0" :width="CHART_W" :height="CHART_H" fill="transparent" @mousemove="onChartMove" @mouseleave="onChartLeave" />
          </svg>

          <div
            v-if="hoverIndex !== null"
            class="dash-chart-tooltip"
            :style="{ left: (chartPuntos[hoverIndex].x / CHART_W) * 100 + '%', top: (chartPuntos[hoverIndex].y / CHART_H) * 100 + '%' }"
          >
            <strong>{{ formatCOP(chartPuntos[hoverIndex].valor) }}</strong>
            <span>{{ etiquetaTooltip(hoverIndex) }}</span>
          </div>
        </div>

        <div class="dash-chart-labels" :style="{ paddingLeft: (PAD_LEFT / CHART_W) * 100 + '%', paddingRight: (PAD_RIGHT / CHART_W) * 100 + '%' }">
          <span v-for="(v, i) in valoresGrafica" :key="i" :class="{ invisible: !mostrarEtiqueta(i) }">{{ etiquetaEje(i) }}</span>
        </div>
      </div>

      <div class="dash-panel">
        <div class="dash-panel-header">
          <h2>Stock bajo</h2>
          <RouterLink to="/admin/productos?estado=stock_bajo" class="dash-panel-link">Ver todo</RouterLink>
        </div>
        <div v-if="!stockBajo.length" class="dash-empty">Todo el inventario está en buen nivel.</div>
        <div v-else class="dash-stock-list">
          <div v-for="item in stockBajo" :key="item.id_inventario" class="dash-stock-item">
            <div class="dash-stock-icon"><i class="ri-archive-2-line"></i></div>
            <div class="dash-stock-info">
              <strong>{{ item.producto.nombre }}</strong>
              <span>Cód: {{ item.producto.codigo_producto }}</span>
            </div>
            <div class="dash-stock-right">
              <strong>{{ item.cantidad_disponible }} und</strong>
              <span class="badge" :class="esCritico(item) ? 'badge-red' : 'badge-yellow'">
                {{ esCritico(item) ? 'Crítico' : 'Advertencia' }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- PRODUCTOS MÁS VENDIDOS + ACTIVIDAD RECIENTE -->
    <div class="dash-grid dash-grid-inv">
      <div class="dash-panel">
        <div class="dash-panel-header">
          <h2>Productos más vendidos</h2>
          <RouterLink to="/admin/reportes" class="dash-panel-link">Ver reportes</RouterLink>
        </div>
        <div v-if="!productosMasVendidos.length" class="dash-empty">Todavía no hay ventas registradas.</div>
        <div v-else class="dash-top-list">
          <div v-for="(item, i) in productosMasVendidos" :key="item.producto.id_producto" class="dash-top-item">
            <span class="dash-top-rank">{{ i + 1 }}</span>
            <img :src="item.producto.imagen_url" :alt="item.producto.nombre" class="dash-top-thumb" />
            <div class="dash-top-info">
              <strong>{{ item.producto.nombre }}</strong>
              <div class="dash-top-bar-track"><div class="dash-top-bar-fill" :style="{ width: item.pct + '%' }"></div></div>
            </div>
            <span class="dash-top-unidades">{{ item.cantidad }} und.</span>
          </div>
        </div>
      </div>

      <div class="dash-panel">
        <div class="dash-panel-header">
          <h2>Actividad reciente</h2>
          <RouterLink to="/admin/ventas" class="dash-panel-link">Ver todo</RouterLink>
        </div>
        <div class="dash-actividad-list">
          <div v-for="(a, i) in actividadReciente" :key="i" class="dash-actividad-item">
            <div class="dash-actividad-icon" :class="`color-${a.color}`"><i :class="a.icono"></i></div>
            <div class="dash-actividad-body">
              <strong>{{ a.titulo }}</strong>
              <span>{{ a.detalle }}</span>
            </div>
            <div class="dash-actividad-meta">
              <span>{{ fechaActividad(a.fecha) }}</span>
              <span class="dash-actividad-tag">{{ a.etiqueta }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dash-hero {
  display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap;
  background: linear-gradient(135deg, var(--secondary) 0%, #2c2c4a 100%);
  border-radius: var(--radius-lg); padding: 26px 32px; margin-bottom: 24px; color: white;
}
.dash-hero-fecha {
  display: flex; align-items: center; gap: 7px; font-size: 0.85rem; color: rgba(255,255,255,0.6);
  text-transform: capitalize; margin-bottom: 10px;
}
.dash-hero-text h1 { font-family: var(--font-main); font-weight: 800; font-size: 1.75rem; margin-bottom: 6px; }
.dash-hero-sub { color: rgba(255,255,255,0.65); font-size: 0.92rem; }
.dash-hero-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.dash-hero-actions .btn-outline-red { background: rgba(255,255,255,0.06); }

.dash-metricas { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; margin-bottom: 24px; }

/* Tarjetas de estadística sin íconos en cajas de colores — esa fórmula (chip pastel + número) es
   la que se veía genérica; en su lugar la jerarquía la da la tipografía: etiqueta pequeña arriba,
   número grande abajo, con un ícono simple sin fondo solo como referencia visual discreta. */
.dash-stat { display: block; background: white; border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 22px 24px; text-decoration: none; color: inherit; transition: var(--transition); }
a.dash-stat:hover { border-color: var(--primary); box-shadow: var(--shadow); }
.dash-stat-label {
  display: flex; align-items: center; gap: 7px; font-family: var(--font-main); font-weight: 700;
  font-size: 0.76rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 13px;
}
.dash-stat-label i { font-size: 0.95rem; color: var(--text-light); }
.dash-stat-value-row { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.dash-stat-value { font-family: var(--font-main); font-weight: 800; font-size: 1.6rem; color: var(--secondary); }
.dash-stat-value.alerta { color: var(--primary); }
.dash-stat-trend { display: inline-flex; align-items: center; font-size: 0.82rem; font-weight: 700; }
.dash-stat-trend.up { color: var(--success); }
.dash-stat-trend.down { color: var(--primary); }
.dash-stat-note { font-size: 0.78rem; color: var(--text-muted); }

.dash-grid { display: grid; grid-template-columns: 1.6fr 1fr; gap: 18px; margin-bottom: 18px; align-items: start; }
.dash-grid-inv { grid-template-columns: 1fr 1.3fr; }
.dash-panel { background: white; border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 22px; margin-bottom: 18px; }
.dash-grid .dash-panel { margin-bottom: 0; }
.dash-panel-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; gap: 10px; flex-wrap: wrap; }
.dash-panel-header h2 { font-family: var(--font-main); font-weight: 700; font-size: 1rem; color: var(--secondary); }
.dash-panel-link { font-size: 0.8rem; font-weight: 600; color: var(--primary); }

.dash-chart-total { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
.dash-chart-total strong { font-family: var(--font-main); font-weight: 800; font-size: 1.4rem; color: var(--secondary); }
.dash-toggle { display: flex; background: var(--off-white); border-radius: 20px; padding: 3px; flex-shrink: 0; }
.dash-toggle button {
  border: none; background: none; padding: 6px 14px; border-radius: 20px; font-family: var(--font-main);
  font-weight: 600; font-size: 0.78rem; color: var(--text-light); cursor: pointer; transition: var(--transition);
}
.dash-toggle button.active { background: white; color: var(--primary); box-shadow: var(--shadow); }

.dash-chart-wrap { position: relative; }
.dash-chart { width: 100%; height: auto; display: block; cursor: crosshair; }
.dash-chart-eje-y { font-size: 9.5px; fill: var(--text-muted); font-family: var(--font-body); }
.dash-chart-tooltip {
  position: absolute; transform: translate(-50%, -115%); background: var(--secondary); color: white;
  border-radius: var(--radius-sm); padding: 8px 12px; font-size: 0.76rem; text-align: center;
  pointer-events: none; white-space: nowrap; box-shadow: var(--shadow-lg); z-index: 5;
}
.dash-chart-tooltip strong { display: block; font-family: var(--font-main); font-weight: 800; font-size: 0.85rem; }
.dash-chart-tooltip span { color: rgba(255,255,255,0.65); text-transform: capitalize; }
.dash-chart-labels { display: flex; justify-content: space-between; margin-top: 8px; padding: 0 14px; }
.dash-chart-labels span { font-size: 0.74rem; color: var(--text-muted); font-weight: 600; }
.dash-chart-labels span.invisible { visibility: hidden; }

.dash-empty { color: var(--text-muted); font-size: 0.85rem; text-align: center; padding: 30px 0; }
.dash-stock-list { display: flex; flex-direction: column; gap: 4px; }
.dash-stock-item { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border); }
.dash-stock-item:last-child { border-bottom: none; }
.dash-stock-icon { width: 36px; height: 36px; border-radius: var(--radius-sm); background: var(--off-white); color: var(--primary); display: flex; align-items: center; justify-content: center; font-size: 1rem; flex-shrink: 0; }
.dash-stock-info { flex: 1; display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.dash-stock-info strong { font-size: 0.83rem; color: var(--secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dash-stock-info span { font-size: 0.72rem; color: var(--text-muted); }
.dash-stock-right { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }
.dash-stock-right strong { font-size: 0.8rem; color: var(--secondary); }

.dash-top-list { display: flex; flex-direction: column; gap: 4px; }
.dash-top-item { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border); }
.dash-top-item:last-child { border-bottom: none; }
.dash-top-rank { width: 20px; flex-shrink: 0; font-family: var(--font-main); font-weight: 800; font-size: 0.85rem; color: var(--text-muted); text-align: center; }
.dash-top-thumb { width: 38px; height: 38px; border-radius: var(--radius-sm); object-fit: cover; flex-shrink: 0; }
.dash-top-info { flex: 1; min-width: 0; }
.dash-top-info strong { display: block; font-size: 0.83rem; color: var(--secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 5px; }
.dash-top-bar-track { height: 5px; border-radius: 3px; background: var(--off-white); overflow: hidden; }
.dash-top-bar-fill { height: 100%; border-radius: 3px; background: var(--primary); }
.dash-top-unidades { font-size: 0.76rem; font-weight: 700; color: var(--text-muted); flex-shrink: 0; }

.dash-actividad-list { display: flex; flex-direction: column; }
.dash-actividad-item { display: flex; align-items: center; gap: 14px; padding: 14px 0; border-bottom: 1px solid var(--border); }
.dash-actividad-item:last-child { border-bottom: none; }
.dash-actividad-icon { width: 42px; height: 42px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 1.1rem; flex-shrink: 0; }
.dash-actividad-icon.color-azul { background: rgba(41,128,185,0.1); color: var(--info); }
.dash-actividad-icon.color-verde { background: rgba(39,174,96,0.1); color: var(--success); }
.dash-actividad-body { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.dash-actividad-body strong { font-size: 0.87rem; color: var(--secondary); text-transform: capitalize; }
.dash-actividad-body span { font-size: 0.78rem; color: var(--text-muted); }
.dash-actividad-meta { display: flex; flex-direction: column; align-items: flex-end; gap: 5px; flex-shrink: 0; }
.dash-actividad-meta span:first-child { font-size: 0.75rem; color: var(--text-muted); }
.dash-actividad-tag { font-size: 0.65rem; font-weight: 700; color: var(--text-muted); background: var(--off-white); padding: 2px 9px; border-radius: 20px; letter-spacing: 0.4px; }

@media (max-width: 1100px) {
  .dash-metricas { grid-template-columns: 1fr 1fr; }
  .dash-grid, .dash-grid-inv { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .dash-metricas { grid-template-columns: 1fr; }
  .dash-hero { flex-direction: column; align-items: stretch; }
  .dash-hero-actions .btn { flex: 1; justify-content: center; }
  .dash-actividad-item { flex-wrap: wrap; }
  .dash-actividad-meta { align-items: flex-start; flex-direction: row; gap: 8px; width: 100%; margin-left: 56px; }
}
</style>
