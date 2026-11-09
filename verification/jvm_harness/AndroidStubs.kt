package android.content
import android.content.res.AssetManager
open class Context {
    open val applicationContext: Context get() = this
    open val assets: AssetManager get() = error("Not supplied")
    private val prefs = mutableMapOf<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { SharedPreferences() }
    companion object { const val MODE_PRIVATE = 0 }
}
class SharedPreferences {
    private val data = mutableMapOf<String,String>()
    fun getString(key: String, default: String?): String? = data[key] ?: default
    fun edit() = Editor(data)
    class Editor(private val data: MutableMap<String,String>) {
        fun putString(key: String, value: String): Editor { data[key]=value;return this }
        fun apply() {}
    }
}
