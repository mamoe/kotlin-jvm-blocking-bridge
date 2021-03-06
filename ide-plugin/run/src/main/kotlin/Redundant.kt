import net.mamoe.kjbb.JvmBlockingBridge

@JvmBlockingBridge
object Redundant {

    // private so no auto @JvmBlockingBridge
    private suspend fun test() {}

    // effectively public so auto @JvmBlockingBridge
    @PublishedApi
    internal suspend fun test2() {
    }

    @JvmStatic
    fun main(args: Array<String>) {

    }
}