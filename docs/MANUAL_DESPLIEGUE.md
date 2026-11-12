# Manual de despliegue y plan de rollback — Acabados y Diseños 1A

Versión del documento: 1.0 · 12/11/2026 · Versión del software: **v1.0.1** (anterior estable: v1.0.0)
Entorno descrito: servidor local (equipo Windows 11 del proyecto). Los mismos pasos sirven en
Linux cambiando los comandos de PowerShell por los de Bash.

## 1. Arquitectura que se despliega

```
Navegador ──HTTP:5173──▶ Frontend Vue 3 (Vite) ──HTTP:8080 /api + JWT──▶ Backend Spring Boot (Tomcat embebido)
                                                                           │ JDBC:3306
                                                                           ▼
                                                                  MariaDB · acabados_disenos_1a
                                                                  (23 tablas · 3 vistas · 2 triggers)
Backend ──SMTP:587 (TLS)──▶ Gmail (verificación de correo, recuperación de contraseña, contacto)
```

## 2. Requisitos

| Componente | Versión probada | Verificar con |
|---|---|---|
| Windows 11 / Linux | 10.0.26200 | `winver` |
| Java (JDK Temurin) | 21.0.12 | `java -version` |
| Maven | 3.9.16 (incluido con `mvnw`) | `.\mvnw.cmd -v` |
| Node.js / npm | 24.22.1 / 11.19.1 | `node -v` / `npm -v` |
| MariaDB Server | 12.3.3 (servicio de Windows) | `mariadb --version` |
| Git | 2.51 | `git --version` |

Puertos libres: **3306** (BD), **8080** (backend), **5173** (frontend).

## 3. Instalación

```powershell
git clone https://github.com/josemartinez0821-design/acabados1a-ecommerce.git
cd acabados1a-ecommerce
git checkout v1.0.1                      # siempre desplegar una versión etiquetada, no "lo último"
```

Base de datos (solo la primera vez, o al migrar a otro servidor):

```powershell
mariadb -u root -p -e "CREATE DATABASE acabados_disenos_1a CHARACTER SET utf8mb4;"
mariadb -u root -p acabados_disenos_1a < respaldo_acabados_disenos_1a_AAAAMMDD.sql
```

## 4. Configuración (la configuración NO está en el código)

1. Backend: copiar `backend/src/main/resources/application.properties.example` a
   `application.properties`. Ese archivo está en `.gitignore` y nunca se sube.
2. Definir los secretos como **variables de entorno** (lista completa en `backend/.env.example`):

   ```powershell
   $env:DB_PASSWORD = "********"; $env:JWT_SECRET = "********"; $env:MAIL_PASSWORD = "********"
   ```
3. Frontend: copiar `frontend/.env.example` a `frontend/.env` y ajustar `VITE_API_BASE_URL`.

## 5. Arranque

```powershell
Get-Service MariaDB                     # debe estar Running (Start-Service MariaDB si no)
cd backend;  .\mvnw.cmd spring-boot:run # esperar: "Started BackendApplication" y "Tomcat started on port 8080"
cd frontend; npm install; npm run dev   # esperar: "Local: http://localhost:5173/"
```

Para producción el frontend se compila (`npm run build`) y la carpeta `dist/` se sirve con un
servidor web (Apache/Nginx) redirigiendo las rutas a `index.html`; el backend se empaqueta con
`.\mvnw.cmd clean package` y se ejecuta con `java -jar target/backend-*.jar`.

## 6. Verificación (pruebas de humo)

| # | Comprobación | Resultado esperado |
|---|---|---|
| 1 | Abrir `http://localhost:5173` y `GET /api/productos` | HTTP 200 |
| 2 | Iniciar sesión (válido / contraseña errónea) | 200 + token / 401 |
| 3 | CRUD de una promoción como administrador | 201 · 200 · 200 · 204 |
| 4 | Consultar el registro en MariaDB | El cambio está persistido |
| 5 | Cliente intenta `/admin` o `POST /api/promociones` | Redirigido / 403 |
| 6 | Cerrar sesión y volver a `/admin` | Pide iniciar sesión |
| 7 | Revisar el log del backend | 0 líneas `ERROR` |
| 8 | Tiempo de respuesta de la API | < 2 s por petición |

## 7. Plan de rollback (volver a la versión anterior)

Se activa si alguna comprobación crítica (1, 2, 4 o 5) falla después de desplegar y no se corrige
en 30 minutos.

1. **Detener** el backend (Ctrl+C) y el frontend.
2. **Respaldar el estado actual** antes de tocar nada (sirve para analizar la falla):
   `mariadb-dump -u root -p --routines --triggers --databases acabados_disenos_1a > fallido_AAAAMMDD.sql`
3. **Volver al código anterior** usando la etiqueta previa: `git checkout <etiqueta_anterior>`
   (si falla `v1.0.1`, la anterior es `v1.0.0`; ver la lista con `git tag -l -n1`).
4. **Restaurar la base de datos** solo si la versión fallida la modificó:
   `mariadb -u root -p acabados_disenos_1a < respaldo_previo_al_despliegue.sql`
5. **Arrancar** de nuevo (sección 5) y **repetir la verificación** (sección 6).
6. **Registrar** el incidente: fecha, versión fallida, causa y responsable.

Regla de oro: **no se despliega sin una etiqueta de versión y un respaldo de la BD tomado el mismo día.**
