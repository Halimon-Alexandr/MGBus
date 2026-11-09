package androidx.lifecycle
import android.app.Application
import kotlinx.coroutines.*
open class AndroidViewModel(val application: Application) {
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
}
val AndroidViewModel.viewModelScope: CoroutineScope get() = scope
