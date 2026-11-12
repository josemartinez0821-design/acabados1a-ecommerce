<script setup>
// RF20 - teléfono de contacto; el formulario es una ampliación sin RF oficial. Canales, mapa de
// ubicación y datos de la empresa (RNF11)
import { ref, computed, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useCatalogStore } from '../stores/catalog'
import { useToast } from '../composables/useToast'
import { DEPARTAMENTOS, MUNICIPIOS_POR_DEPARTAMENTO } from '../data/colombia'
import api from '../services/api'

const auth = useAuthStore()
const catalog = useCatalogStore()
const { showToast } = useToast()

// "Cotización" y "Servicio" no están aquí a propósito: ya tienen su propio flujo dedicado
// en /cotizaciones (selección de productos/servicios, seguimiento de estado, etc.).
const MOTIVOS = [
  { value: 'informacion', label: 'Información', icon: 'ri-information-line' },
  { value: 'pedido', label: 'Pedido', icon: 'ri-shopping-bag-line' },
  { value: 'soporte', label: 'Soporte', icon: 'ri-customer-service-line' },
  { value: 'otro', label: 'Otro', icon: 'ri-more-line' },
]

const form = ref({
  motivo: 'informacion',
  nombre: auth.usuario ? `${auth.usuario.nombre} ${auth.usuario.apellido}` : '',
  telefono: auth.usuario?.telefono || '',
  email: auth.usuario?.email || '',
  departamento: auth.usuario?.departamento || '',
  ciudad: auth.usuario?.ciudad || '',
  mensaje: '',
})
const LIMITE_MENSAJE = 1000
const enviando = ref(false)

const municipiosDisponibles = computed(() => MUNICIPIOS_POR_DEPARTAMENTO[form.value.departamento] || [])

watch(() => form.value.departamento, () => {
  if (!municipiosDisponibles.value.includes(form.value.ciudad)) {
    form.value.ciudad = ''
  }
})

async function enviarMensaje() {
  enviando.value = true
  try {
    await api.post('/contacto', { ...form.value })
    showToast('¡Mensaje enviado! Te responderemos en máximo 24 horas hábiles.', 'success')
    form.value.mensaje = ''
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo enviar el mensaje. Intenta de nuevo.', 'danger')
  } finally {
    enviando.value = false
  }
}

// ── Horario: abierto/cerrado en vivo, Lunes a Sábado 8am-6pm (según configuración real) ──
const estaAbierto = computed(() => {
  const now = new Date()
  const dia = now.getDay()
  const horaNum = now.getHours() * 100 + now.getMinutes()
  return dia >= 1 && dia <= 6 && horaNum >= 800 && horaNum < 1800
})

// ── Mapa: ubicación real (Google Maps). URLs de "ver"/"cómo llegar" centralizadas en catalog.configuracion. ──
const MAPA_EMBED_SRC = 'https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3986.0645783307673!2d-75.73041212524959!3d2.4855776568737062!2m3!1f0!2f0!3f0!3m2!1i1024!2i768!4f13.1!3m3!1m2!1s0x8e3ad63b7d2f55a1%3A0x5178431799aaf7e9!2sPINTURAS%20TESACOL!5e0!3m2!1ses!2sco!4v1785820901587!5m2!1ses!2sco'

// ── FAQ ──
const FAQS = [
  { icono: 'ri-truck-line', pregunta: '¿Hacen envíos a domicilio?', respuesta: 'Sí, realizamos envíos a Tesalia y municipios cercanos. El tiempo de entrega es de 24 a 48 horas hábiles.' },
  { icono: 'ri-bank-card-line', pregunta: '¿Qué métodos de pago aceptan?', respuesta: 'Aceptamos efectivo, transferencia bancaria, Nequi, Daviplata y tarjetas débito/crédito.' },
  { icono: 'ri-file-list-line', pregunta: '¿Cómo solicito una cotización?', respuesta: 'Desde nuestra página de Cotizaciones, por WhatsApp, o completando este formulario indicando las medidas del área y los productos de interés. Respondemos en menos de 24 horas.' },
  { icono: 'ri-shield-check-line', pregunta: '¿Tienen garantía en los productos?', respuesta: 'Todos nuestros productos tienen garantía de 30 días por defectos de fábrica, además de la garantía propia de cada marca.' },
  { icono: 'ri-tools-line', pregunta: '¿Ofrecen servicio de instalación?', respuesta: 'Sí, contamos con técnicos especializados para instalación de Drywall, PVC, aplicación de pintura y otros acabados.' },
]
const faqAbierta = ref(null)
function toggleFaq(i) {
  faqAbierta.value = faqAbierta.value === i ? null : i
}
</script>

<template>
  <section class="services-hero">
    <div class="services-hero-bg">
      <img src="https://images.unsplash.com/photo-1562259929-b4e1fd3aef09?w=1600&q=80" alt="Contacto Acabados 1A" />
    </div>
    <div class="container">
      <div class="breadcrumb">
        <span class="breadcrumb-item"><RouterLink to="/"><i class="ri-home-4-line"></i> Inicio</RouterLink></span>
        <span class="breadcrumb-sep"><i class="ri-arrow-right-s-line"></i></span>
        <span class="breadcrumb-item active">Contacto</span>
      </div>
      <h1 class="services-hero-title">Hablemos sobre tu <em>próximo proyecto</em></h1>
      <p class="services-hero-desc">
        Visítanos en nuestra tienda, escríbenos por WhatsApp o envíanos un mensaje.
      </p>
    </div>
  </section>

  <!-- CANALES DE CONTACTO -->
  <section class="section-sm" style="background:var(--off-white);">
    <div class="container">
      <div class="contacto-channels-grid">
        <a :href="`tel:${catalog.configuracion.telefono}`" class="contacto-channel-card">
          <div class="contacto-channel-icon contacto-icon-red"><i class="ri-phone-line"></i></div>
          <div class="contacto-channel-label">Teléfono</div>
          <div class="contacto-channel-value">{{ catalog.configuracion.telefono }}</div>
          <div class="contacto-channel-desc">Llámanos directamente</div>
        </a>
        <a :href="`https://wa.me/${catalog.configuracion.whatsapp}?text=Hola!%20Quiero%20más%20información`" target="_blank" rel="noopener" class="contacto-channel-card">
          <div class="contacto-channel-icon contacto-icon-green"><i class="ri-whatsapp-line"></i></div>
          <div class="contacto-channel-label">WhatsApp</div>
          <div class="contacto-channel-value">{{ catalog.configuracion.telefono }}</div>
          <div class="contacto-channel-desc">Respuesta inmediata</div>
        </a>
        <a :href="`mailto:${catalog.configuracion.email}`" class="contacto-channel-card">
          <div class="contacto-channel-icon contacto-icon-blue"><i class="ri-mail-send-line"></i></div>
          <div class="contacto-channel-label">Email</div>
          <div class="contacto-channel-value">{{ catalog.configuracion.email }}</div>
          <div class="contacto-channel-desc">Respuesta en 24h</div>
        </a>
        <a href="#mapa-section" class="contacto-channel-card">
          <div class="contacto-channel-icon contacto-icon-dark"><i class="ri-map-pin-2-line"></i></div>
          <div class="contacto-channel-label">Dirección</div>
          <div class="contacto-channel-value">{{ catalog.configuracion.direccion }}</div>
          <div class="contacto-channel-desc">{{ catalog.configuracion.departamento }}</div>
        </a>
      </div>
    </div>
  </section>

  <!-- FORMULARIO + MAPA + SIDEBAR -->
  <section class="section" style="background:white;">
    <div class="container">
      <div class="contacto-layout">
        <div>
          <!-- FORMULARIO -->
          <div class="contacto-card">
            <h2 class="contacto-card-title"><i class="ri-send-plane-line"></i> Envíanos un mensaje</h2>
            <p class="contacto-card-subtitle">¿Tienes dudas sobre un producto, necesitas asesoría o quieres solicitar una cotización? ¡Con gusto te ayudamos!</p>

            <form @submit.prevent="enviarMensaje">
              <div class="form-group">
                <label class="form-label required">Motivo de contacto</label>
                <div class="motivo-grid">
                  <div
                    v-for="m in MOTIVOS"
                    :key="m.value"
                    class="motivo-card"
                    :class="{ active: form.motivo === m.value }"
                    @click="form.motivo = m.value"
                  >
                    <i :class="m.icon"></i> {{ m.label }}
                  </div>
                </div>
              </div>

              <div class="contacto-card-divider"></div>

              <div class="auth-form-row">
                <div class="form-group">
                  <label class="form-label required">Nombre completo</label>
                  <input v-model="form.nombre" type="text" class="form-control" placeholder="Tu nombre" required autocomplete="name" />
                </div>
                <div class="form-group">
                  <label class="form-label required">Teléfono</label>
                  <input v-model="form.telefono" type="tel" class="form-control" placeholder="310 000 0000" required autocomplete="tel" />
                </div>
              </div>
              <div class="form-group">
                <label class="form-label required">Correo electrónico</label>
                <input v-model="form.email" type="email" class="form-control" placeholder="correo@ejemplo.com" required autocomplete="email" />
              </div>
              <div class="auth-form-row">
                <div class="form-group">
                  <label class="form-label">Departamento</label>
                  <select v-model="form.departamento" class="form-control">
                    <option value="" disabled>Seleccionar...</option>
                    <option v-for="depto in DEPARTAMENTOS" :key="depto" :value="depto">{{ depto }}</option>
                  </select>
                </div>
                <div class="form-group">
                  <label class="form-label">Ciudad / Municipio</label>
                  <select v-model="form.ciudad" class="form-control" :disabled="!form.departamento">
                    <option value="" disabled>{{ form.departamento ? 'Seleccionar...' : 'Elige primero un departamento' }}</option>
                    <option v-for="mun in municipiosDisponibles" :key="mun" :value="mun">{{ mun }}</option>
                  </select>
                </div>
              </div>
              <div class="form-group">
                <label class="form-label required">Mensaje</label>
                <textarea
                  v-model="form.mensaje"
                  class="form-control"
                  rows="5"
                  placeholder="Cuéntanos en qué podemos ayudarte..."
                  :maxlength="LIMITE_MENSAJE"
                  required
                ></textarea>
                <div class="pqrs-char-count" :class="{ 'near-limit': form.mensaje.length > LIMITE_MENSAJE - 100 }">
                  {{ form.mensaje.length }} / {{ LIMITE_MENSAJE }}
                </div>
              </div>

              <button type="submit" class="btn btn-primary btn-block" style="font-size:0.95rem;padding:14px;" :disabled="enviando">
                <i class="ri-send-plane-fill"></i> {{ enviando ? 'Enviando...' : 'Enviar Mensaje' }}
              </button>
            </form>
          </div>

          <!-- MAPA -->
          <div class="contacto-card" style="margin-top:24px;" id="mapa-section">
            <h2 class="contacto-card-title"><i class="ri-map-2-line"></i> Nuestra ubicación</h2>
            <p class="contacto-card-subtitle">{{ catalog.configuracion.direccion }}, {{ catalog.configuracion.departamento }}, Colombia</p>
            <div class="contacto-map-wrapper">
              <iframe
                :src="MAPA_EMBED_SRC"
                width="100%" height="320" style="border:0;" allowfullscreen=""
                loading="lazy" referrerpolicy="strict-origin-when-cross-origin"
                title="Ubicación Acabados 1A"
              ></iframe>
            </div>
            <div class="d-flex gap-10 mt-20" style="flex-wrap:wrap;">
              <a :href="catalog.configuracion.mapa_ver_url" target="_blank" rel="noopener" class="btn btn-outline-red btn-sm">
                <i class="ri-external-link-line"></i> Abrir en Google Maps
              </a>
              <a :href="catalog.configuracion.mapa_llegar_url" target="_blank" rel="noopener" class="btn btn-primary btn-sm">
                <i class="ri-navigation-line"></i> Cómo llegar
              </a>
            </div>
          </div>
        </div>

        <!-- SIDEBAR -->
        <div class="contacto-sidebar-col">
          <div class="sidebar-card" style="margin-bottom:20px;">
            <div class="sidebar-section">
              <div class="sidebar-title"><i class="ri-store-line"></i> {{ catalog.configuracion.nombre_empresa }}</div>
              <div class="info-item">
                <div class="info-item-icon"><i class="ri-map-pin-line"></i></div>
                <div>
                  <div class="info-item-label">Dirección</div>
                  <div class="info-item-value">{{ catalog.configuracion.direccion }}, {{ catalog.configuracion.departamento }}</div>
                </div>
              </div>
              <div class="info-item">
                <div class="info-item-icon"><i class="ri-phone-line"></i></div>
                <div>
                  <div class="info-item-label">Teléfono</div>
                  <div class="info-item-value">{{ catalog.configuracion.telefono }}</div>
                </div>
              </div>
              <div class="info-item">
                <div class="info-item-icon"><i class="ri-mail-line"></i></div>
                <div>
                  <div class="info-item-label">Correo electrónico</div>
                  <div class="info-item-value">{{ catalog.configuracion.email }}</div>
                </div>
              </div>
              <div v-if="catalog.configuracion.nit" class="info-item">
                <div class="info-item-icon"><i class="ri-building-line"></i></div>
                <div>
                  <div class="info-item-label">NIT</div>
                  <div class="info-item-value">{{ catalog.configuracion.nit }}</div>
                </div>
              </div>
            </div>
          </div>

          <div class="sidebar-card" style="margin-bottom:20px;">
            <div class="sidebar-section">
              <div class="sidebar-title"><i class="ri-time-line"></i> Horario de atención</div>
              <div class="contacto-horario-row">
                <span class="contacto-horario-day"><span class="contacto-horario-dot open"></span>Lunes a Sábado</span>
                <span class="contacto-horario-time">8:00am – 6:00pm</span>
              </div>
              <div class="contacto-horario-row">
                <span class="contacto-horario-day"><span class="contacto-horario-dot closed"></span>Domingos y festivos</span>
                <span class="contacto-horario-closed">Cerrado</span>
              </div>
              <div class="contacto-horario-status" :class="estaAbierto ? 'open' : 'closed'">
                <i :class="estaAbierto ? 'ri-checkbox-circle-fill' : 'ri-close-circle-fill'"></i>
                <span v-if="estaAbierto">Ahora mismo estamos <strong>abiertos</strong> — ¡Ven a visitarnos!</span>
                <span v-else>Actualmente estamos <strong>cerrados</strong> — Escríbenos por WhatsApp.</span>
              </div>
            </div>
          </div>

          <div class="sidebar-card">
            <div class="sidebar-section">
              <div class="sidebar-title"><i class="ri-share-line"></i> Redes sociales</div>
              <div class="contacto-social-grid">
                <a :href="`https://wa.me/${catalog.configuracion.whatsapp}`" target="_blank" rel="noopener" class="contacto-social-btn whatsapp">
                  <span class="contacto-social-icon"><i class="ri-whatsapp-fill"></i></span> WhatsApp
                </a>
                <a :href="catalog.configuracion.facebook" target="_blank" rel="noopener" class="contacto-social-btn facebook">
                  <span class="contacto-social-icon"><i class="ri-facebook-fill"></i></span> Facebook
                </a>
                <a :href="catalog.configuracion.instagram" target="_blank" rel="noopener" class="contacto-social-btn instagram">
                  <span class="contacto-social-icon"><i class="ri-instagram-line"></i></span> Instagram
                </a>
                <a :href="catalog.configuracion.tiktok" target="_blank" rel="noopener" class="contacto-social-btn tiktok">
                  <span class="contacto-social-icon"><i class="ri-tiktok-fill"></i></span> TikTok
                </a>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- FAQ -->
  <section class="section" style="background:var(--off-white);">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow">Preguntas frecuentes</span>
        <h2 class="section-title">¿Tienes dudas? Aquí las respondemos</h2>
      </div>
      <div style="max-width:760px;margin:0 auto;">
        <div v-for="(f, i) in FAQS" :key="f.pregunta" class="faq-item">
          <div class="faq-question" :class="{ active: faqAbierta === i }" @click="toggleFaq(i)">
            <span><i :class="f.icono" style="color:var(--primary);margin-right:10px;"></i>{{ f.pregunta }}</span>
            <i class="ri-arrow-down-s-line faq-chevron"></i>
          </div>
          <div class="faq-answer" :class="{ open: faqAbierta === i }">
            <div class="faq-answer-inner">{{ f.respuesta }}</div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- CTA FINAL -->
  <section class="home-cta-section">
    <div class="container">
      <h2 class="home-cta-title">¿Listo para empezar tu proyecto?</h2>
      <p class="home-cta-sub">Escríbenos ahora y recibe asesoría personalizada sin compromiso.</p>
      <div class="home-cta-actions">
        <a :href="`https://wa.me/${catalog.configuracion.whatsapp}?text=Hola!%20Quiero%20asesoría%20para%20mi%20proyecto`" target="_blank" rel="noopener" class="btn btn-white btn-lg">
          <i class="ri-whatsapp-line"></i> Escribir por WhatsApp
        </a>
        <RouterLink to="/cotizaciones" class="home-cta-link">Solicitar cotización</RouterLink>
      </div>
    </div>
  </section>
</template>
