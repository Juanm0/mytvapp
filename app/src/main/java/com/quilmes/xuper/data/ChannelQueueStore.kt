package com.quilmes.xuper.data

/**
 * Cola de "canal anterior / siguiente" del reproductor en vivo.
 *
 * La pestaña Argentina deja acá la lista exacta que la persona tenía a la vista cuando tocó un canal
 * (la fila de principales, los secundarios de un principal, la grilla o un grupo de "Otros canales"),
 * así el zapping salta al canal de AL LADO y no al siguiente por orden alfabético de toda la lista.
 * Si el canal que se reproduce no está en esta cola (se abrió desde otra pantalla), el reproductor usa
 * la cola de siempre (`M3uRepository.getChannelQueue`).
 */
object ChannelQueueStore {
    @Volatile private var queue: List<Title> = emptyList()

    fun set(titles: List<Title>) {
        queue = titles.distinctBy { it.id }
    }

    fun clear() {
        queue = emptyList()
    }

    /** La cola guardada si tiene al menos 2 canales e incluye [id]; si no, null. */
    fun queueFor(id: String): List<Title>? = queue.takeIf { list -> list.size > 1 && list.any { it.id == id } }
}
