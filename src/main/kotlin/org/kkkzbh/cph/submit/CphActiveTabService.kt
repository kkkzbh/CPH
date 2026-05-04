package org.kkkzbh.cph.submit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.messages.Topic
import java.time.Duration
import java.time.Instant

internal data class CphActiveTab(
    val url: String,
    val title: String,
    val receivedAt: Instant,
)

internal interface CphActiveTabListener {
    fun activeTabChanged(tab: CphActiveTab?)

    companion object {
        val TOPIC: Topic<CphActiveTabListener> =
            Topic.create("CPH active tab changed", CphActiveTabListener::class.java)
    }
}

@Service(Service.Level.APP)
internal class CphActiveTabService {
    @Volatile
    private var last: CphActiveTab? = null

    fun update(url: String, title: String) {
        val tab = CphActiveTab(url = url, title = title, receivedAt = Instant.now())
        last = tab
        ApplicationManager.getApplication().messageBus
            .syncPublisher(CphActiveTabListener.TOPIC)
            .activeTabChanged(tab)
    }

    fun current(maxAge: Duration = DEFAULT_MAX_AGE): CphActiveTab? {
        val snapshot = last ?: return null
        if (Duration.between(snapshot.receivedAt, Instant.now()) > maxAge) return null
        return snapshot
    }

    fun lastEver(): CphActiveTab? = last

    companion object {
        val DEFAULT_MAX_AGE: Duration = Duration.ofMinutes(10)

        fun getInstance(): CphActiveTabService =
            ApplicationManager.getApplication().getService(CphActiveTabService::class.java)
    }
}
