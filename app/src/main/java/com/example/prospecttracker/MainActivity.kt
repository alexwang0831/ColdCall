package com.example.prospecttracker

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// 1. 陌生開發名單資料結構
data class Prospect(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val location: String,
    val chatNotes: String = "",
    val lastEditor: String = "我",
    val updatedAt: Long = System.currentTimeMillis()
)

// 2. 客戶訂貨需求資料結構
data class CustomerOrder(
    val id: String = UUID.randomUUID().toString(),
    val customerName: String,
    val customerNumber: String,
    val category: String,
    val details: String = "",
    val lastEditor: String = "我",
    val updatedAt: Long = System.currentTimeMillis()
)

val PRODUCT_CATEGORIES = listOf("紐崔萊", "雅姿", "家用科技", "家潔產品", "其它");

object StorageAndCloudManager {
    private const val PREF = "prospect_tracker_prefs";
    private const val KEY_LIST = "prospects_json";
    private const val KEY_ORDERS = "orders_json";
    private const val KEY_CLOUD_URL = "cloud_db_url";
    private const val KEY_USER_NAME = "user_nickname";

    fun getCloudUrl(ctx: Context): String {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_CLOUD_URL, "") ?: "";
    }

    fun setCloudConfig(ctx: Context, url: String, nickname: String) {
        val cleanUrl = url.trim().removeSuffix("/").removeSuffix(".json");
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_CLOUD_URL, cleanUrl)
            .putString(KEY_USER_NAME, nickname.trim().ifEmpty { "夥伴" })
            .apply();
    }

    fun getUserName(ctx: Context): String {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_USER_NAME, "業務A") ?: "業務A";
    }

    // --- 陌生開發本地存取 ---
    fun saveLocal(ctx: Context, list: List<Prospect>) {
        val arr = JSONArray();
        for (p in list) {
            val o = JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("location", p.location);
            o.put("chatNotes", p.chatNotes);
            o.put("lastEditor", p.lastEditor);
            o.put("updatedAt", p.updatedAt);
            arr.put(o);
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    fun loadLocal(ctx: Context): List<Prospect> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LIST, null) ?: return emptyList();
        return parseJsonToList(s);
    }

    // --- 客戶訂貨本地存取 ---
    fun saveOrdersLocal(ctx: Context, list: List<CustomerOrder>) {
        val arr = JSONArray();
        for (item in list) {
            val o = JSONObject();
            o.put("id", item.id);
            o.put("customerName", item.customerName);
            o.put("customerNumber", item.customerNumber);
            o.put("category", item.category);
            o.put("details", item.details);
            o.put("lastEditor", item.lastEditor);
            o.put("updatedAt", item.updatedAt);
            arr.put(o);
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ORDERS, arr.toString()).apply();
    }

    fun loadOrdersLocal(ctx: Context): List<CustomerOrder> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ORDERS, null) ?: return emptyList();
        return parseOrdersJsonToList(s);
    }

    fun parseJsonToList(jsonStr: String): List<Prospect> {
        val res = mutableListOf<Prospect>();
        try {
            val trimmed = jsonStr.trim();
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed);
                for (i in 0 until arr.length()) {
                    if (!arr.isNull(i)) res.add(parseProspectObj(arr.getJSONObject(i)));
                }
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed);
                if (root.has("prospects")) {
                    val arr = root.getJSONArray("prospects");
                    for (i in 0 until arr.length()) res.add(parseProspectObj(arr.getJSONObject(i)));
                } else {
                    val keys = root.keys();
                    while (keys.hasNext()) {
                        val k = keys.next();
                        val o = root.optJSONObject(k);
                        if (o != null && o.has("name")) res.add(parseProspectObj(o));
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace(); }
        return res.sortedByDescending { it.updatedAt };
    }

    private fun parseProspectObj(o: JSONObject): Prospect {
        return Prospect(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "未命名"),
            location = o.optString("location", "未填寫地點"),
            chatNotes = o.optString("chatNotes", ""),
            lastEditor = o.optString("lastEditor", "夥伴"),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        );
    }

    fun parseOrdersJsonToList(jsonStr: String): List<CustomerOrder> {
        val res = mutableListOf<CustomerOrder>();
        try {
            val trimmed = jsonStr.trim();
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed);
                for (i in 0 until arr.length()) {
                    if (!arr.isNull(i)) res.add(parseOrderObj(arr.getJSONObject(i)));
                }
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed);
                if (root.has("orders")) {
                    val arr = root.getJSONArray("orders");
                    for (i in 0 until arr.length()) res.add(parseOrderObj(arr.getJSONObject(i)));
                } else {
                    val keys = root.keys();
                    while (keys.hasNext()) {
                        val k = keys.next();
                        val o = root.optJSONObject(k);
                        if (o != null && o.has("customerName")) res.add(parseOrderObj(o));
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace(); }
        return res.sortedByDescending { it.updatedAt };
    }

    private fun parseOrderObj(o: JSONObject): CustomerOrder {
        return CustomerOrder(
            id = o.optString("id", UUID.randomUUID().toString()),
            customerName = o.optString("customerName", "未命名"),
            customerNumber = o.optString("customerNumber", "無編號"),
            category = o.optString("category", "紐崔萊"),
            details = o.optString("details", ""),
            lastEditor = o.optString("lastEditor", "夥伴"),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        );
    }

    // 打包完整備份檔 (同時包含陌生開發與客戶訂貨)
    fun createBackupJson(prospects: List<Prospect>, orders: List<CustomerOrder>): String {
        val root = JSONObject();
        val pArr = JSONArray();
        for (p in prospects) {
            val o = JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("location", p.location);
            o.put("chatNotes", p.chatNotes);
            o.put("lastEditor", p.lastEditor);
            o.put("updatedAt", p.updatedAt);
            pArr.put(o);
        }
        val oArr = JSONArray();
        for (item in orders) {
            val o = JSONObject();
            o.put("id", item.id);
            o.put("customerName", item.customerName);
            o.put("customerNumber", item.customerNumber);
            o.put("category", item.category);
            o.put("details", item.details);
            o.put("lastEditor", item.lastEditor);
            o.put("updatedAt", item.updatedAt);
            oArr.put(o);
        }
        root.put("backupTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()));
        root.put("prospects", pArr);
        root.put("orders", oArr);
        return root.toString(2);
    }

    // --- 陌生開發 Firebase 雲端同步 ---
    suspend fun pushItemToCloud(baseUrl: String, item: Prospect): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false;
        try {
            val endpoint = "$baseUrl/prospects/${item.id}.json";
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT";
                setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                doOutput = true;
                connectTimeout = 5000;
                readTimeout = 5000;
            };
            val o = JSONObject().apply {
                put("id", item.id);
                put("name", item.name);
                put("location", item.location);
                put("chatNotes", item.chatNotes);
                put("lastEditor", item.lastEditor);
                put("updatedAt", item.updatedAt);
            };
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(o.toString()); };
            val code = conn.responseCode;
            conn.disconnect();
            code in 200..299
        } catch (e: Exception) { false }
    }

    suspend fun deleteItemFromCloud(baseUrl: String, id: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false;
        try {
            val endpoint = "$baseUrl/prospects/$id.json";
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE";
                connectTimeout = 5000;
            };
            val code = conn.responseCode;
            conn.disconnect();
            code in 200..299
        } catch (e: Exception) { false }
    }

    suspend fun fetchAllFromCloud(baseUrl: String): List<Prospect>? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null;
        try {
            val endpoint = "$baseUrl/prospects.json";
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET";
                connectTimeout = 5000;
                readTimeout = 5000;
            };
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() };
                conn.disconnect();
                if (text == "null" || text.isBlank()) emptyList() else parseJsonToList(text)
            } else {
                conn.disconnect();
                null
            }
        } catch (e: Exception) { null }
    }

    // --- 客戶訂貨 Firebase 雲端同步 ---
    suspend fun pushOrderToCloud(baseUrl: String, item: CustomerOrder): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false;
        try {
            val endpoint = "$baseUrl/orders/${item.id}.json";
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT";
                setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                doOutput = true;
                connectTimeout = 5000;
                readTimeout = 5000;
            };
            val o = JSONObject().apply {
                put("id", item.id);
                put("customerName", item.customerName);
                put("customerNumber", item.customerNumber);
                put("category", item.category);
                put("details", item.details);
                put("lastEditor", item.lastEditor);
                put("updatedAt", item.updatedAt);
            };
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(o.toString()); };
            val code = conn.responseCode;
            conn.disconnect();
            code in 200..299
        } catch (e: Exception) { false }
    }

    suspend fun deleteOrderFromCloud(baseUrl: String, id: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false;
        try {
            val endpoint = "$baseUrl/orders/$id.json";
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE";
                connectTimeout = 5000;
            };
            val code = conn.responseCode;
            conn.disconnect();
            code in 200..299
        } catch (e: Exception) { false }
    }

    suspend fun fetchAllOrdersFromCloud(baseUrl: String): List<CustomerOrder>? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null;
        try {
            val endpoint = "$baseUrl/orders.json";
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET";
                connectTimeout = 5000;
                readTimeout = 5000;
            };
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() };
                conn.disconnect();
                if (text == "null" || text.isBlank()) emptyList() else parseOrdersJsonToList(text)
            } else {
                conn.disconnect();
                null
            }
        } catch (e: Exception) { null }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        setContent {
            MaterialTheme {
                MainAppEntry()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppEntry() {
    val ctx = LocalContext.current;
    val scope = rememberCoroutineScope();

    // 載入陌生開發名單
    val prospects = remember {
        val saved = StorageAndCloudManager.loadLocal(ctx);
        if (saved.isNotEmpty()) mutableStateListOf(*saved.toTypedArray())
        else mutableStateListOf(
            Prospect(
                name = "王小明 (範例名單)",
                location = "台北市信義區星巴克",
                chatNotes = "[10/25 14:30 業務A] 初次認識，聊到他對投資理財有興趣，約下週二再聯絡。",
                lastEditor = "業務A"
            )
        )
    };

    // 載入客戶訂貨名單
    val orders = remember {
        val saved = StorageAndCloudManager.loadOrdersLocal(ctx);
        if (saved.isNotEmpty()) mutableStateListOf(*saved.toTypedArray())
        else mutableStateListOf(
            CustomerOrder(
                customerName = "陳小姐 (範例訂貨)",
                customerNumber = "A123456",
                category = "紐崔萊",
                details = "雙X 2盒、優質蛋白素 1罐，下週三面交。",
                lastEditor = "業務A"
            )
        )
    };

    // 頁面狀態："home" (首頁選擇), "prospects" (陌生開發), "orders" (客戶訂貨)
    var currentSection by remember { mutableStateOf("home") };
    var selectedProspectId by remember { mutableStateOf<String?>(null) };
    var selectedOrderId by remember { mutableStateOf<String?>(null) };

    var showAddProspectDialog by remember { mutableStateOf(false) };
    var showAddOrderDialog by remember { mutableStateOf(false) };
    var showCloudDialog by remember { mutableStateOf(false) };

    var cloudUrl by remember { mutableStateOf(StorageAndCloudManager.getCloudUrl(ctx)) };
    var userName by remember { mutableStateOf(StorageAndCloudManager.getUserName(ctx)) };
    var syncStatus by remember { mutableStateOf("本地儲存模式") };

    // 每 4 秒自動與雲端同步「陌生開發」與「客戶訂貨」
    LaunchedEffect(cloudUrl) {
        while (isActive) {
            if (cloudUrl.isNotBlank()) {
                val remoteProspects = StorageAndCloudManager.fetchAllFromCloud(cloudUrl);
                val remoteOrders = StorageAndCloudManager.fetchAllOrdersFromCloud(cloudUrl);

                if (remoteProspects != null && remoteOrders != null) {
                    syncStatus = "🟢 多人雲端即時同步中";
                    if (remoteProspects.isEmpty() && prospects.isNotEmpty()) {
                        for (p in prospects) StorageAndCloudManager.pushItemToCloud(cloudUrl, p);
                    } else {
                        prospects.clear();
                        prospects.addAll(remoteProspects);
                        StorageAndCloudManager.saveLocal(ctx, prospects);
                    }

                    if (remoteOrders.isEmpty() && orders.isNotEmpty()) {
                        for (o in orders) StorageAndCloudManager.pushOrderToCloud(cloudUrl, o);
                    } else {
                        orders.clear();
                        orders.addAll(remoteOrders);
                        StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                    }
                } else {
                    syncStatus = "⚠️ 雲端連線失敗 (已存於本地)";
                }
            } else {
                syncStatus = "📁 本地模式 (點右上角 ☁️ 開啟多人雲端)";
            }
            delay(4000);
        }
    }

    // 匯出 JSON 備份檔至 Google Drive
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                val json = StorageAndCloudManager.createBackupJson(prospects, orders);
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)); };
                Toast.makeText(ctx, "☁️ 陌生開發與客戶訂貨已完整備份至雲端硬碟！", Toast.LENGTH_LONG).show();
            } catch (e: Exception) {
                Toast.makeText(ctx, "備份失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

    // 從 Google Drive 讀取 JSON 備份檔還原
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val content = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "";
                val restoredProspects = StorageAndCloudManager.parseJsonToList(content);
                val restoredOrders = StorageAndCloudManager.parseOrdersJsonToList(content);

                if (restoredProspects.isNotEmpty()) {
                    prospects.clear();
                    prospects.addAll(restoredProspects);
                    StorageAndCloudManager.saveLocal(ctx, prospects);
                    if (cloudUrl.isNotBlank()) {
                        scope.launch { for (p in restoredProspects) StorageAndCloudManager.pushItemToCloud(cloudUrl, p); }
                    }
                }
                if (restoredOrders.isNotEmpty()) {
                    orders.clear();
                    orders.addAll(restoredOrders);
                    StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                    if (cloudUrl.isNotBlank()) {
                        scope.launch { for (o in restoredOrders) StorageAndCloudManager.pushOrderToCloud(cloudUrl, o); }
                    }
                }
                Toast.makeText(ctx, "✅ 成功還原：${restoredProspects.size} 筆開發名單、${restoredOrders.size} 筆訂貨紀錄！", Toast.LENGTH_LONG).show();
            } catch (e: Exception) {
                Toast.makeText(ctx, "讀取失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

    val currentProspect = prospects.find { it.id == selectedProspectId };
    val currentOrder = orders.find { it.id == selectedOrderId };

    // 畫面導覽判斷
    if (currentProspect != null) {
        BackHandler { selectedProspectId = null; }
        ProspectDetailScreen(
            prospect = currentProspect,
            currentUserName = userName,
            onBack = { selectedProspectId = null; },
            onSave = { updated ->
                val idx = prospects.indexOfFirst { it.id == updated.id };
                if (idx >= 0) prospects[idx] = updated else prospects.add(0, updated);
                prospects.sortByDescending { it.updatedAt };
                StorageAndCloudManager.saveLocal(ctx, prospects);
                if (cloudUrl.isNotBlank()) {
                    scope.launch { StorageAndCloudManager.pushItemToCloud(cloudUrl, updated); }
                }
                Toast.makeText(ctx, "✅ 已儲存並同步至雲端", Toast.LENGTH_SHORT).show();
            },
            onDelete = { id ->
                prospects.removeAll { it.id == id };
                StorageAndCloudManager.saveLocal(ctx, prospects);
                selectedProspectId = null;
                if (cloudUrl.isNotBlank()) {
                    scope.launch { StorageAndCloudManager.deleteItemFromCloud(cloudUrl, id); }
                }
            }
        );
    } else if (currentOrder != null) {
        BackHandler { selectedOrderId = null; }
        OrderDetailScreen(
            order = currentOrder,
            currentUserName = userName,
            onBack = { selectedOrderId = null; },
            onSave = { updated ->
                val idx = orders.indexOfFirst { it.id == updated.id };
                if (idx >= 0) orders[idx] = updated else orders.add(0, updated);
                orders.sortByDescending { it.updatedAt };
                StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                if (cloudUrl.isNotBlank()) {
                    scope.launch { StorageAndCloudManager.pushOrderToCloud(cloudUrl, updated); }
                }
                Toast.makeText(ctx, "✅ 訂貨資料已儲存並同步", Toast.LENGTH_SHORT).show();
            },
            onDelete = { id ->
                orders.removeAll { it.id == id };
                StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                selectedOrderId = null;
                if (cloudUrl.isNotBlank()) {
                    scope.launch { StorageAndCloudManager.deleteOrderFromCloud(cloudUrl, id); }
                }
            }
        );
    } else if (currentSection == "home") {
        // 進入 Application 後的首頁功能選擇畫面
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("業務隨身助理", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(syncStatus, fontSize = 11.sp, color = Color(0xFFD0E8FF))
                        }
                    },
                    actions = {
                        TextButton(onClick = { showCloudDialog = true }) {
                            Text("☁️ 雲端與多人", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E3A5F), titleContentColor = Color.White)
                )
