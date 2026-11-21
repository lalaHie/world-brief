package tw.worldbrief.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

class WorldBriefApplication : Application(), ImageLoaderFactory {
    override fun onCreate() { super.onCreate(); runCatching { FirebaseSetup.initialize(this) } }
    override fun newImageLoader():ImageLoader=ImageLoader.Builder(this).diskCache { DiskCache.Builder().directory(cacheDir.resolve("news_images")).maxSizeBytes(200L*1024*1024).build() }.build()
}

object FirebaseSetup {
    private fun prefs(c:Context)=c.getSharedPreferences("firebase-client",Context.MODE_PRIVATE)
    fun initialize(c:Context) {
        if(FirebaseApp.getApps(c).isNotEmpty())return
        val raw=prefs(c).getString("config",null) ?: return
        FirebaseApp.initializeApp(c,options(c,raw))
    }
    private fun options(c:Context,raw:String):FirebaseOptions {
        val root=JSONObject(raw); val project=root.getJSONObject("project_info"); val clients=root.getJSONArray("client")
        val client=(0 until clients.length()).map{clients.getJSONObject(it)}.firstOrNull{it.getJSONObject("client_info").getJSONObject("android_client_info").getString("package_name")==c.packageName} ?: error("設定檔不屬於世界晨報")
        return FirebaseOptions.Builder().setApplicationId(client.getJSONObject("client_info").getString("mobilesdk_app_id")).setApiKey(client.getJSONArray("api_key").getJSONObject(0).getString("current_key")).setProjectId(project.getString("project_id")).setGcmSenderId(project.getString("project_number")).build()
    }
    fun import(c:Context,raw:String) { val options=options(c,raw); if(FirebaseApp.getApps(c).isEmpty())FirebaseApp.initializeApp(c,options); else require(FirebaseApp.getInstance().options.applicationId==options.applicationId){"此版本已綁定另一個 Firebase App"}; prefs(c).edit().putString("config",raw).apply();NewsNotifications.subscribe(c) }
}
