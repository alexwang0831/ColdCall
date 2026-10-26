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
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF4F6F9))
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("請選擇要使用的功能", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A5F))
                Spacer(modifier = Modifier.height(8.dp))
                Text("目前登入身分：$userName", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(32.dp))

                // 選項 1：陌生開發名單
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(135.dp)
                        .clickable { currentSection = "prospects" },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A5F)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("📋 陌生開發名單", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("記錄認識對象、地點與多人聊天追蹤筆記 (目前 ${prospects.size} 筆)", fontSize = 14.sp, color = Color(0xFFD0E8FF))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 選項 2：現有客戶訂貨需求
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(135.dp)
                        .clickable { currentSection = "orders" },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF00796B)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🛒 客戶訂貨需求", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("記錄客戶姓名、編號、5大產品分類與訂貨明細 (目前 ${orders.size} 筆)", fontSize = 14.sp, color = Color(0xFFB2DFDB))
                    }
                }
            }
        }
    } else if (currentSection == "prospects") {
        // 陌生開發名單列表頁 (維持原本設計)
        BackHandler { currentSection = "home"; }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("陌生開發名單 (${prospects.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(syncStatus, fontSize = 11.sp, color = Color(0xFFD0E8FF))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { currentSection = "home" }) {
                            Icon(Icons.Default.Home, contentDescription = "回首頁", tint = Color.White)
                        }
                    },
                    actions = {
                        if (cloudUrl.isNotBlank()) {
                            IconButton(onClick = {
                                scope.launch {
                                    val remote = StorageAndCloudManager.fetchAllFromCloud(cloudUrl);
                                    if (remote != null) {
                                        prospects.clear();
                                        prospects.addAll(remote);
                                        StorageAndCloudManager.saveLocal(ctx, prospects);
                                        Toast.makeText(ctx, "已同步最新雲端名單", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "同步", tint = Color.White)
                            }
                        }
                        TextButton(onClick = { showCloudDialog = true }) {
                            Text("☁️ 雲端", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E3A5F), titleContentColor = Color.White)
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddProspectDialog = true },
                    containerColor = Color(0xFF06C755),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新增")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新增開發對象", fontWeight = FontWeight.Bold)
                }
            }
        ) { innerPadding ->
            if (prospects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Text("目前尚無名單，請點右下角「＋新增開發對象」", color = Color.Gray, fontSize = 15.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFF4F6F9)).padding(innerPadding).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(prospects, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { selectedProspectId = item.id },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A5F))
                                    val timeStr = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt));
                                    Text("更新：$timeStr (${item.lastEditor})", fontSize = 11.sp, color = Color.Gray)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("📍 地點：${item.location}", fontSize = 14.sp, color = Color(0xFF333333), fontWeight = FontWeight.Medium)
                                if (item.chatNotes.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val preview = item.chatNotes.lines().lastOrNull { it.isNotBlank() } ?: "";
                                    Text("💬 最新紀錄：$preview", fontSize = 13.sp, color = Color(0xFF666666), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (currentSection == "orders") {
        // 現有客戶訂貨需求列表頁
        BackHandler { currentSection = "home"; }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("客戶訂貨需求 (${orders.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(syncStatus, fontSize = 11.sp, color = Color(0xFFB2DFDB))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { currentSection = "home" }) {
                            Icon(Icons.Default.Home, contentDescription = "回首頁", tint = Color.White)
                        }
                    },
                    actions = {
                        if (cloudUrl.isNotBlank()) {
                            IconButton(onClick = {
                                scope.launch {
                                    val remote = StorageAndCloudManager.fetchAllOrdersFromCloud(cloudUrl);
                                    if (remote != null) {
                                        orders.clear();
                                        orders.addAll(remote);
                                        StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                                        Toast.makeText(ctx, "已同步最新訂貨紀錄", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "同步", tint = Color.White)
                            }
                        }
                        TextButton(onClick = { showCloudDialog = true }) {
                            Text("☁️ 雲端", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF00796B), titleContentColor = Color.White)
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddOrderDialog = true },
                    containerColor = Color(0xFF00796B),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新增訂貨")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新增客戶訂貨", fontWeight = FontWeight.Bold)
                }
            }
        ) { innerPadding ->
            if (orders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Text("目前尚無訂貨紀錄，請點右下角「＋新增客戶訂貨」", color = Color.Gray, fontSize = 15.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFF4F6F9)).padding(innerPadding).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(orders, key = { it.id }) { order ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { selectedOrderId = order.id },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${order.customerName} (#${order.customerNumber})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00796B))
                                    val timeStr = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(order.updatedAt));
                                    Text("更新：$timeStr (${order.lastEditor})", fontSize = 11.sp, color = Color.Gray)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = Color(0xFFE0F2F1),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "🏷️ 產品分類：${order.category}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00695C),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                if (order.details.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("📝 詳細資料：${order.details}", fontSize = 14.sp, color = Color(0xFF444444), maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 新增陌生開發對象彈窗
    if (showAddProspectDialog) {
        var newName by remember { mutableStateOf("") };
        var newLoc by remember { mutableStateOf("") };
        var newNote by remember { mutableStateOf("") };

        AlertDialog(
            onDismissRequest = { showAddProspectDialog = false },
            title = { Text("新增陌生開發對象", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("對象名稱 (必填)") }, singleLine = true)
                    OutlinedTextField(value = newLoc, onValueChange = { newLoc = it }, label = { Text("認識地點 (如：信義區星巴克)") }, singleLine = true)
                    OutlinedTextField(value = newNote, onValueChange = { newNote = it }, label = { Text("初步聊天內容 (選填)") }, maxLines = 3)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            val timeTag = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date());
                            val initChat = if (newNote.isNotBlank()) "[$timeTag $userName] ${newNote.trim()}" else "";
                            val created = Prospect(
                                name = newName.trim(),
                                location = newLoc.trim().ifEmpty { "未填寫地點" },
                                chatNotes = initChat,
                                lastEditor = userName
                            );
                            prospects.add(0, created);
                            StorageAndCloudManager.saveLocal(ctx, prospects);
                            if (cloudUrl.isNotBlank()) {
                                scope.launch { StorageAndCloudManager.pushItemToCloud(cloudUrl, created); }
                            }
                            showAddProspectDialog = false;
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06C755))
                ) { Text("新增並備份") }
            },
            dismissButton = {
                TextButton(onClick = { showAddProspectDialog = false }) { Text("取消") }
            }
        )
    }

    // 新增客戶訂貨需求彈窗
    if (showAddOrderDialog) {
        var cName by remember { mutableStateOf("") };
        var cNum by remember { mutableStateOf("") };
        var selectedCat by remember { mutableStateOf(PRODUCT_CATEGORIES[0]) };
        var cDetails by remember { mutableStateOf("") };

        AlertDialog(
            onDismissRequest = { showAddOrderDialog = false },
            title = { Text("新增客戶訂貨需求", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = cName,
                        onValueChange = { cName = it },
                        label = { Text("客戶姓名 (必填)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cNum,
                        onValueChange = { cNum = it },
                        label = { Text("客戶編號") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("選擇產品分類：", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF00796B))
                    PRODUCT_CATEGORIES.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedCat = cat }
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = (selectedCat == cat),
                                onClick = { selectedCat = cat }
                            )
                            Text(text = cat, fontSize = 15.sp)
                        }
                    }
                    OutlinedTextField(
                        value = cDetails,
                        onValueChange = { cDetails = it },
                        label = { Text("詳細資料 (訂貨品項、數量、備註)") },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cName.isNotBlank()) {
                            val created = CustomerOrder(
                                customerName = cName.trim(),
                                customerNumber = cNum.trim().ifEmpty { "無編號" },
                                category = selectedCat,
                                details = cDetails.trim(),
                                lastEditor = userName
                            );
                            orders.add(0, created);
                            StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                            if (cloudUrl.isNotBlank()) {
                                scope.launch { StorageAndCloudManager.pushOrderToCloud(cloudUrl, created); }
                            }
                            showAddOrderDialog = false;
                        } else {
                            Toast.makeText(ctx, "請輸入客戶姓名", Toast.LENGTH_SHORT).show();
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                ) { Text("新增並備份") }
            },
            dismissButton = {
                TextButton(onClick = { showAddOrderDialog = false }) { Text("取消") }
            }
        )
    }

    // 雲端設定與檔案備份彈窗
    if (showCloudDialog) {
        var inputUrl by remember { mutableStateOf(cloudUrl) };
        var inputNick by remember { mutableStateOf(userName) };

        AlertDialog(
            onDismissRequest = { showCloudDialog = false },
            title = { Text("☁️ 雲端備份 & 多人協作設定", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("【功能 1：多人即時共同編輯】\n貼上 Firebase Realtime Database 網址（陌生開發與客戶訂貨皆會自動同步）：", fontSize = 13.sp, color = Color.DarkGray)
                    OutlinedTextField(
                        value = inputNick,
                        onValueChange = { inputNick = it },
                        label = { Text("您的顯示名稱 (如：業務A)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        label = { Text("雲端資料庫網址") },
                        placeholder = { Text("https://xxx.firebasedatabase.app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            StorageAndCloudManager.setCloudConfig(ctx, inputUrl, inputNick);
                            cloudUrl = StorageAndCloudManager.getCloudUrl(ctx);
                            userName = StorageAndCloudManager.getUserName(ctx);
                            showCloudDialog = false;
                            Toast.makeText(ctx, "已更新雲端連線設定！", Toast.LENGTH_SHORT).show();
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
                    ) { Text("💾 儲存多人連線設定") }

                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("【功能 2：Google 雲端硬碟檔案備份】", fontSize = 13.sp, color = Color.DarkGray)
                    Button(
                        onClick = {
                            showCloudDialog = false;
                            val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date());
                            exportLauncher.launch("業務助理完整備份_$dateTag.json");
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) { Text("☁️ 匯出完整資料 (.json) 至 Google 雲端") }

                    OutlinedButton(
                        onClick = {
                            showCloudDialog = false;
                            importLauncher.launch(arrayOf("application/json", "*/*"));
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📥 從 Google 雲端硬碟讀取備份檔還原") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCloudDialog = false }) { Text("關閉") }
            }
        )
    }
}

// 陌生開發詳細內容頁 (完全保留原本設計)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProspectDetailScreen(
    prospect: Prospect,
    currentUserName: String,
    onBack: () -> Unit,
    onSave: (Prospect) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by remember(prospect.id) { mutableStateOf(prospect.name) };
    var location by remember(prospect.id) { mutableStateOf(prospect.location) };
    var chatNotes by remember(prospect.id, prospect.updatedAt) { mutableStateOf(prospect.chatNotes) };
    var quickChatInput by remember { mutableStateOf("") };
    var showDeleteConfirm by remember { mutableStateOf(false) };

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("對象詳細內容", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除", tint = Color(0xFFFFB4AB))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E3A5F), titleContentColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("對象名稱") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("認識地點") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = quickChatInput,
                    onValueChange = { quickChatInput = it },
                    label = { Text("新增一句聊天紀錄 (以 $currentUserName 身分發言)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (quickChatInput.isNotBlank()) {
                            val timeTag = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date());
                            val newLine = "[$timeTag $currentUserName] ${quickChatInput.trim()}";
                            chatNotes = if (chatNotes.isBlank()) newLine else "$chatNotes\n$newLine";
                            quickChatInput = "";
                            val updated = prospect.copy(
                                name = name.trim(),
                                location = location.trim(),
                                chatNotes = chatNotes,
                                lastEditor = currentUserName,
                                updatedAt = System.currentTimeMillis()
                            );
                            onSave(updated);
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06C755))
                ) { Text("紀錄") }
            }

            OutlinedTextField(
                value = chatNotes,
                onValueChange = { chatNotes = it },
                label = { Text("完整聊天內容 / 開發筆記 (可自由編輯)") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                maxLines = Int.MAX_VALUE
            )

            Button(
                onClick = {
                    val updated = prospect.copy(
                        name = name.trim().ifEmpty { "未命名" },
                        location = location.trim().ifEmpty { "未填寫地點" },
                        chatNotes = chatNotes,
                        lastEditor = currentUserName,
                        updatedAt = System.currentTimeMillis()
                    );
                    onSave(updated);
                    onBack();
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
            ) {
                Text("💾 儲存修改並同步至雲端", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("確認刪除名單？") },
                text = { Text("確定要刪除「${prospect.name}」的所有資料與聊天紀錄嗎？") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false;
                            onDelete(prospect.id);
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) { Text("確定刪除") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}

// 客戶訂貨需求詳細內容與編輯頁
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    order: CustomerOrder,
    currentUserName: String,
    onBack: () -> Unit,
    onSave: (CustomerOrder) -> Unit,
    onDelete: (String) -> Unit
) {
    var customerName by remember(order.id) { mutableStateOf(order.customerName) };
    var customerNumber by remember(order.id) { mutableStateOf(order.customerNumber) };
    var category by remember(order.id, order.updatedAt) { mutableStateOf(order.category) };
    var details by remember(order.id, order.updatedAt) { mutableStateOf(order.details) };
    var showDeleteConfirm by remember { mutableStateOf(false) };

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("客戶訂貨詳細資料", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除", tint = Color(0xFFFFB4AB))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF00796B), titleContentColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it },
                    label = { Text("客戶姓名") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = customerNumber,
                    onValueChange = { customerNumber = it },
                    label = { Text("客戶編號") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Text("產品分類（點選切換）：", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF00796B))

            // 5種產品分類選擇按鈕列
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRODUCT_CATEGORIES.take(3).forEach { cat ->
                        val isSelected = (category == cat);
                        Button(
                            onClick = { category = cat },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF00796B) else Color(0xFFE0E0E0),
                                contentColor = if (isSelected) Color.White else Color.Black
                            ),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(cat, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRODUCT_CATEGORIES.drop(3).forEach { cat ->
                        val isSelected = (category == cat);
                        Button(
                            onClick = { category = cat },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF00796B) else Color(0xFFE0E0E0),
                                contentColor = if (isSelected) Color.White else Color.Black
                            ),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(cat, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text("詳細資料 (訂貨品項、數量、交貨狀況等文字方塊)") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                maxLines = Int.MAX_VALUE
            )

            Button(
                onClick = {
                    val updated = order.copy(
                        customerName = customerName.trim().ifEmpty { "未命名" },
                        customerNumber = customerNumber.trim().ifEmpty { "無編號" },
                        category = category,
                        details = details,
                        lastEditor = currentUserName,
                        updatedAt = System.currentTimeMillis()
                    );
                    onSave(updated);
                    onBack();
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
            ) {
                Text("💾 儲存訂貨資料並同步至雲端", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("確認刪除訂貨紀錄？") },
                text = { Text("確定要刪除「${order.customerName} (#${order.customerNumber})」的訂貨紀錄嗎？") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false;
                            onDelete(order.id);
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) { Text("確定刪除") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}
