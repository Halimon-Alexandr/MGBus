package android.app
import android.content.Context
import android.content.res.AssetManager
import java.io.File
open class Application(root: File): Context() { override val assets = AssetManager(root) }
