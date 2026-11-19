# Funcionalidades que diferencian a RIVEM de RIVeR escritorio

RIVeR escritorio procesa un video ya grabado. El teléfono está **en el sitio**, tiene **sensores** y se usa **muchas
veces en el mismo lugar**. Las funcionalidades de abajo aprovechan eso. Todo lo que agregan se guarda en claves extra
del proyecto que RIVeR ignora, así que la compatibilidad con escritorio se mantiene.

Estado: ✅ hecho · 🚧 en curso · ⬜ pendiente.

## Prioridad (las 3 recomendadas)

| # | Funcionalidad | Estado |
|---|---|---|
| 1 | **Perfil de sitio + curva nivel–caudal**: guardar la georreferenciación, la sección y la batimetría de una estación; en la siguiente visita solo grabar e ingresar el nivel. Con varios aforos, ajustar la curva H–Q | 🚧 |
| 2 | **Distancias con ARKit / LiDAR**: tocar los puntos de control en la pantalla y que el iPhone mida las 6 distancias, sin cinta | 🚧 |
| 3 | **Semáforo de calidad + incertidumbre**: nota de confianza del aforo y Q ± U (95 %) por Monte Carlo | 🚧 |

## 1. Medir en el sitio sin cinta

| Funcionalidad | Qué aporta | Factibilidad |
|---|---|---|
| Distancias con ARKit / LiDAR | Elimina la cinta, lo más lento y propenso a error del aforo oblicuo | LiDAR preciso hasta ~5 m; más lejos, ARKit visual (cm–dm). Validar contra cinta |
| Pose de la cámara (ARKit + gravedad) | Altura e inclinación conocidas; en cenital o puente da el tamaño de píxel sin puntos | Media-alta |
| Intrínsecos reales (`AVCameraCalibrationData`) | Corrige la distorsión sin tablero de calibración | Fase F7 |
| Estabilización con giroscopio | Grabar sin trípode | Fase F7, alta |

## 2. Sitios recurrentes

| Funcionalidad | Qué aporta | Factibilidad |
|---|---|---|
| Perfil de sitio | Georreferenciación + sección + batimetría guardadas; la siguiente visita es grabar + nivel → caudal | Alta |
| Realineación automática | Si el trípode quedó distinto, alinear la imagen nueva con la de referencia (Vision) y reutilizar los puntos | Media |
| Curva nivel–caudal | Ajuste Q = a·(h − h₀)ᵇ con los aforos del sitio; estimar Q desde el nivel | Alta |
| Lectura de la regla limnimétrica | Leer el nivel en la mira desde la foto (manual u OCR) | Manual fácil, OCR medio |

## 3. Calidad en el momento

| Funcionalidad | Qué aporta | Factibilidad |
|---|---|---|
| Vista previa de calidad al apuntar | PIV ligero en vivo: ¿hay trazadores?, ¿reflejos?, ¿regrabar? | Media (~1 Hz) |
| Semáforo del aforo | Residuos de georreferenciación, % medido, estaciones dudosas, dispersión entre pares | Alta |
| Incertidumbre del caudal | Errores de distancias, nivel y PIV propagados (Monte Carlo) → Q ± U 95 % | Media |

## 4. Contexto y registro

| Funcionalidad | Qué aporta | Factibilidad |
|---|---|---|
| GPS, brújula, fecha y hora | Cada aforo ubicado sin escribir; mapa de sitios (MapKit) | Alta |
| Fotos del sitio y notas | Fotos de puntos, regla y condiciones, adjuntas al informe | Alta |
| Informe en el sitio | PDF con mapa, fotos, caudal e incertidumbre, compartido al instante | Alta (el informe ya existe) |
| Sincronización (iCloud / carpeta) | El aforo llega solo a la PC con RIVeR | Media |

## 5. Otras fuentes de video

| Funcionalidad | Qué aporta | Factibilidad |
|---|---|---|
| Telemetría de dron (DJI) | Altura y ángulo del gimbal → tamaño de píxel automático en cenital | Media |
| Estación fija / monitoreo | Aforo cada X minutos durante una crecida, con alertas por umbral | Media |

## Mejoras pendientes de la interfaz (diferidas)

1. La extracción alternativa pierde el último frame (88 de 89).
2. Mostrar "—" en lugar de 0,00 en las estaciones de orilla (tabla e informe).
3. Escala de colores ajustable o bloqueable.
4. Marcar automáticamente las estaciones dudosas.
5. Semáforo de residuos en la georreferenciación.
6. Datos de campo: GPS, fecha y hora, notas, sitio.
