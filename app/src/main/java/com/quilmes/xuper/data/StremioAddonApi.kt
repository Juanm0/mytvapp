package com.quilmes.xuper.data

/**
 * Archivo de compatibilidad vacío.
 *
 * La implementación vigente está en StremioRepository.kt y usa HttpURLConnection + org.json,
 * que ya vienen disponibles en Android. No declarar aquí StremioStream ni StremioRepository:
 * duplicarlos provoca Redeclaration y rompe la compilación. Tampoco se necesita Retrofit/Gson.
 */
