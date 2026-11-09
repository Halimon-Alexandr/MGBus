package android.content.res
import java.io.File
import java.io.InputStream
class AssetManager(private val root: File) { fun open(name: String): InputStream = File(root,name).inputStream() }
