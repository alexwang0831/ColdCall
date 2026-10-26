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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.style.TextDecoration
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

// 2. 單筆訂貨記錄對話框 (支援完成狀態與時間由新到舊排序)
data class OrderRecord(
    val id: String = UUID.randomUUID().toString(),
    val details: String,
    val isCompleted: Boolean = false,
    val createdBy: String = "我",
    val timestamp: Long = System.currentTimeMillis()
)

// 3. 客戶訂貨主檔 (每位客戶可包含多筆不同時間的訂貨對話框)
data class CustomerOrder(
    val id: String = UUID.randomUUID().toString(),
    val customerName: String,
    val customerNumber: String,
    val records: List<OrderRecord> = emptyList(),
    val lastEditor: String = "我",
    val updatedAt: Long = System.currentTimeMillis()
)

object StorageAndCloudManager {
    private const val PREF = "amway_assistant_prefs_v2";
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
    fun serializeOrderObj(item: CustomerOrder): JSONObject {
        val o = JSONObject();
        o.put("id", item.id);
        o.put("customerName", item.customerName);
        o.put("customerNumber", item.customerNumber);
        o.put("lastEditor", item.lastEditor);
        o.put("updatedAt", item.updatedAt);
        val recArr = JSONArray();
        for (r in item.records.sortedByDescending { it.timestamp }) {
            val ro = JSONObject();
            ro.put("id", r.id);
            ro.put("details", r.details);
            ro.put("isCompleted", r.isCompleted);
            ro.put("createdBy", r.createdBy);
            ro.put("timestamp", r.timestamp);
            recArr.put(ro);
        }
        o.put("records", recArr);
        return o;
    }

    fun saveOrdersLocal(ctx: Context, list: List<CustomerOrder>) {
        val arr = JSONArray();
        for (item in list) {
            arr.put(serializeOrderObj(item));
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
        val recs = mutableListOf<OrderRecord>();
        val recArr = o.optJSONArray("records");
        if (recArr != null) {
            for (i in 0 until recArr.length()) {
                val ro = recArr.optJSONObject(i);
                if (ro != null) {
                    recs.add(
                        OrderRecord(
                            id = ro.optString("id", UUID.randomUUID().toString()),
                            details = ro.optString("details", ""),
                            isCompleted = ro.optBoolean("isCompleted", false),
                            createdBy = ro.optString("createdBy", "夥伴"),
                            timestamp = ro.optLong("timestamp", System.currentTimeMillis())
                        )
                    );
                }
            }
        } else {
            val legacyDetails = o.optString("details", "");
            if (legacyDetails.isNotBlank()) {
                recs.add(
                    OrderRecord(
                        details = legacyDetails,
                        isCompleted = false,
                        createdBy = o.optString("lastEditor", "夥伴"),
                        timestamp = o.optLong("updatedAt", System.currentTimeMillis())
                    )
                );
            }
        }
        return CustomerOrder(
            id = o.optString("id", UUID.randomUUID().toString()),
            customerName = o.optString("customerName", "未命名"),
            customerNumber = o.optString("customerNumber", "無編號"),
            records = recs.sortedByDescending { it.timestamp },
            lastEditor = o.optString("lastEditor", "夥伴"),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        );
    }

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
            oArr.put(serializeOrderObj(item));
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
            val o = serializeOrderObj(item);
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

    val prospects = remember {
        val saved = StorageAndCloudManager.loadLocal(ctx);
        if (saved.isNotEmpty()) mutableStateListOf(*saved.toTypedArray())
        else mutableStateListOf(
            Prospect(
                name = "王小明 (範例名單)",
                location = "台北市信義區星巴克",
                chatNotes = "[10/25 14:30 業務A] 初次認識，聊到他對營養保健有興趣，約下週二再聯絡。",
                lastEditor = "業務A"
            )
        )
    };

    val orders = remember {
        val saved = StorageAndCloudManager.loadOrdersLocal(ctx);
        if (saved.isNotEmpty()) mutableStateListOf(*saved.toTypedArray())
        else mutableStateListOf(
            CustomerOrder(
                customerName = "陳小姐 (範例客戶)",
                customerNumber = "A123456",
                records = listOf(
                    OrderRecord(
                        details = "優質蛋白素 2罐、雙X 1盒，約下週三面交。",
                        isCompleted = false,
                        createdBy = "業務A",
                        timestamp = System.currentTimeMillis()
                    ),
                    OrderRecord(
                        details = "洗碗精 2瓶、LOC 1瓶 (已交貨付款)。",
                        isCompleted = true,
                        createdBy = "業務A",
                        timestamp = System.currentTimeMillis() - 86400000L
                    )
                ),
                lastEditor = "業務A"
            )
        )
    };

    var currentSection by remember { mutableStateOf("home") };
    var selectedProspectId by remember { mutableStateOf<String?>(null) };
    var selectedOrderId by remember { mutableStateOf<String?>(null) };

    var showAddProspectDialog by remember { mutableStateOf(false) };
    var showAddCustomerDialog by remember { mutableStateOf(false) };
    var showCloudDialog by remember { mutableStateOf(false) };

    var cloudUrl by remember { mutableStateOf(StorageAndCloudManager.getCloudUrl(ctx)) };
    var userName by remember { mutableStateOf(StorageAndCloudManager.getUserName(ctx)) };
    var syncStatus by remember { mutableStateOf("本地儲存模式") };

    // 每 4 秒自動與雲端同步
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

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                val json = StorageAndCloudManager.createBackupJson(prospects, orders);
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)); };
                Toast.makeText(ctx, "☁️ 完整資料已備份至雲端硬碟！", Toast.LENGTH_LONG).show();
            } catch (e: Exception) {
                Toast.makeText(ctx, "備份失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

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
                Toast.makeText(ctx, "✅ 成功還原：${restoredProspects.size} 筆開發名單、${restoredOrders.size} 位客戶訂貨紀錄！", Toast.LENGTH_LONG).show();
            } catch (e: Exception) {
                Toast.makeText(ctx, "讀取失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

    val currentProspect = prospects.find { it.id == selectedProspectId };
    val currentOrder = orders.find { it.id == selectedOrderId };

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
        CustomerOrderTimelineScreen(
            order = currentOrder,
            currentUserName = userName,
            onBack = { selectedOrderId = null; },
            onUpdateOrder = { updated ->
                val idx = orders.indexOfFirst { it.id == updated.id };
                if (idx >= 0) orders[idx] = updated else orders.add(0, updated);
                orders.sortByDescending { it.updatedAt };
                StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                if (cloudUrl.isNotBlank()) {
                    scope.launch { StorageAndCloudManager.pushOrderToCloud(cloudUrl, updated); }
                }
            },
            onDeleteCustomer = { id ->
                orders.removeAll { it.id == id };
                StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                selectedOrderId = null;
                if (cloudUrl.isNotBlank()) {
                    scope.launch { StorageAndCloudManager.deleteOrderFromCloud(cloudUrl, id); }
                }
            }
        );
    } else if (currentSection == "home") {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("安麗隨身助理", fontWeight = FontWeight.Bold, fontSize = 19.sp)
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
                Text("安麗隨身助理", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A5F))
                Spacer(modifier = Modifier.height(6.dp))
                Text("請選擇要使用的功能（目前身分：$userName）", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(32.dp))

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

                val pendingCount = orders.sumOf { c -> c.records.count { !it.isCompleted } };
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
                        Text("共 ${orders.size} 位客戶｜⏳ 待處理訂單：$pendingCount 筆", fontSize = 14.sp, color = Color(0xFFB2DFDB))
                    }
                }
            }
        }
    } else if (currentSection == "prospects") {
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
        BackHandler { currentSection = "home"; }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("客戶訂貨名單 (${orders.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                    onClick = { showAddCustomerDialog = true },
                    containerColor = Color(0xFF00796B),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新增客戶")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("新增訂貨客戶", fontWeight = FontWeight.Bold)
                }
            }
        ) { innerPadding ->
            if (orders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Text("目前尚無客戶訂貨紀錄，請點右下角「＋新增訂貨客戶」", color = Color.Gray, fontSize = 15.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFF4F6F9)).padding(innerPadding).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(orders, key = { it.id }) { customer ->
                        val sortedRecs = customer.records.sortedByDescending { it.timestamp };
                        val pendingCount = sortedRecs.count { !it.isCompleted };
                        val completedCount = sortedRecs.count { it.isCompleted };
                        val latestRec = sortedRecs.firstOrNull();

                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { selectedOrderId = customer.id },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${customer.customerName} (#${customer.customerNumber})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00796B))
                                    Surface(
                                        color = if (pendingCount > 0) Color(0xFFFFF3E0) else Color(0xFFE8F5E9),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        val badgeText = if (pendingCount > 0) "⏳ 待處理 $pendingCount 筆" else "✅ 全部完成 ($completedCount)";
                                        val badgeColor = if (pendingCount > 0) Color(0xFFE65100) else Color(0xFF2E7D32);
                                        Text(
                                            text = badgeText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                if (latestRec != null) {
                                    val recTime = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(latestRec.timestamp));
                                    val statusIcon = if (latestRec.isCompleted) "✅" else "⏳";
                                    Text(
                                        text = "$statusIcon 最新 ($recTime)：${latestRec.details}",
                                        fontSize = 14.sp,
                                        color = Color(0xFF444444),
                                        maxLines = 2
                                    )
                                } else {
                                    Text("尚無訂貨明細，點此進入新增第一筆訂貨", fontSize = 13.sp, color = Color.Gray)
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

    // 新增訂貨客戶彈窗
    if (showAddCustomerDialog) {
        var cName by remember { mutableStateOf("") };
        var cNum by remember { mutableStateOf("") };
        var firstOrderDetails by remember { mutableStateOf("") };

        AlertDialog(
            onDismissRequest = { showAddCustomerDialog = false },
            title = { Text("新增訂貨客戶", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    OutlinedTextField(
                        value = firstOrderDetails,
                        onValueChange = { firstOrderDetails = it },
                        label = { Text("本次訂貨詳細資料 (選填，進入後可隨時新增)") },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cName.isNotBlank()) {
                            val initRecords = if (firstOrderDetails.isNotBlank()) {
                                listOf(
                                    OrderRecord(
                                        details = firstOrderDetails.trim(),
                                        isCompleted = false,
                                        createdBy = userName,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            } else emptyList();

                            val created = CustomerOrder(
                                customerName = cName.trim(),
                                customerNumber = cNum.trim().ifEmpty { "無編號" },
                                records = initRecords,
                                lastEditor = userName,
                                updatedAt = System.currentTimeMillis()
                            );
                            orders.add(0, created);
                            StorageAndCloudManager.saveOrdersLocal(ctx, orders);
                            if (cloudUrl.isNotBlank()) {
                                scope.launch { StorageAndCloudManager.pushOrderToCloud(cloudUrl, created); }
                            }
                            showAddCustomerDialog = false;
                            selectedOrderId = created.id;
                        } else {
                            Toast.makeText(ctx, "請輸入客戶姓名", Toast.LENGTH_SHORT).show();
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                ) { Text("新增並進入客戶頁") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomerDialog = false }) { Text("取消") }
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
                            exportLauncher.launch("安麗隨身助理備份_$dateTag.json");
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

// 陌生開發詳細內容頁
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

// 客戶訂貨歷史對話框頁面 (每次點選客戶可新增一筆詳細資料對話框，由新到舊排列，並可一鍵標記完成狀態)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerOrderTimelineScreen(
    order: CustomerOrder,
    currentUserName: String,
    onBack: () -> Unit,
    onUpdateOrder: (CustomerOrder) -> Unit,
    onDeleteCustomer: (String) -> Unit
) {
    val ctx = LocalContext.current;
    var customerName by remember(order.id, order.updatedAt) { mutableStateOf(order.customerName) };
    var customerNumber by remember(order.id, order.updatedAt) { mutableStateOf(order.customerNumber) };
    var newOrderText by remember { mutableStateOf("") };
    var editingRecord by remember { mutableStateOf<OrderRecord?>(null) };
    var showDeleteCustomerConfirm by remember { mutableStateOf(false) };

    val sortedRecords = remember(order.records) {
        order.records.sortedByDescending { it.timestamp }
    };

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${order.customerName} 的訂貨紀錄", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (customerName.trim() != order.customerName || customerNumber.trim() != order.customerNumber) {
                            onUpdateOrder(
                                order.copy(
                                    customerName = customerName.trim().ifEmpty { "未命名" },
                                    customerNumber = customerNumber.trim().ifEmpty { "無編號" },
                                    lastEditor = currentUserName,
                                    updatedAt = System.currentTimeMillis()
                                )
                            );
                        }
                        onBack();
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteCustomerConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除客戶", tint = Color(0xFFFFB4AB))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF00796B), titleContentColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF4F6F9))
                .padding(innerPadding)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                    Button(
                        onClick = {
                            onUpdateOrder(
                                order.copy(
                                    customerName = customerName.trim().ifEmpty { "未命名" },
                                    customerNumber = customerNumber.trim().ifEmpty { "無編號" },
                                    lastEditor = currentUserName,
                                    updatedAt = System.currentTimeMillis()
                                )
                            );
                            Toast.makeText(ctx, "✅ 已更新客戶基本資料", Toast.LENGTH_SHORT).show();
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F2F1)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        text = "➕ 新增本次訂貨詳細資料（送出後自動排在最上方）：",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00695C)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newOrderText,
                            onValueChange = { newOrderText = it },
                            placeholder = { Text("輸入本次訂貨品項、數量、交貨備註...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newOrderText.isNotBlank()) {
                                    val newRec = OrderRecord(
                                        details = newOrderText.trim(),
                                        isCompleted = false,
                                        createdBy = currentUserName,
                                        timestamp = System.currentTimeMillis()
                                    );
                                    val updatedList = listOf(newRec) + order.records;
                                    val updatedOrder = order.copy(
                                        customerName = customerName.trim().ifEmpty { "未命名" },
                                        customerNumber = customerNumber.trim().ifEmpty { "無編號" },
                                        records = updatedList.sortedByDescending { it.timestamp },
                                        lastEditor = currentUserName,
                                        updatedAt = System.currentTimeMillis()
                                    );
                                    newOrderText = "";
                                    onUpdateOrder(updatedOrder);
                                    Toast.makeText(ctx, "✅ 已新增訂貨紀錄並同步雲端", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(ctx, "請先輸入訂貨詳細資料", Toast.LENGTH_SHORT).show();
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B)),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Text("新增\n訂單", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            val pendingCount = sortedRecords.count { !it.isCompleted };
            val doneCount = sortedRecords.count { it.isCompleted };
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📋 歷次訂貨對話框 (由新到舊，共 ${sortedRecords.size} 筆)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF333333)
                )
                Text(
                    text = "⏳待處理 $pendingCount ｜ ✅已完成 $doneCount",
                    fontSize = 12.sp,
                    color = Color(0xFF00796B),
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (sortedRecords.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("目前尚無訂貨紀錄，請由上方輸入框新增第一筆訂單！", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sortedRecords, key = { it.id }) { rec ->
                        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(rec.timestamp));
                        val cardBg = if (rec.isCompleted) Color(0xFFE8F5E9) else Color.White;

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingRecord = rec },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = rec.isCompleted,
                                            onCheckedChange = { checked ->
                                                val updatedRecs = order.records.map {
                                                    if (it.id == rec.id) it.copy(isCompleted = checked) else it
                                                };
                                                onUpdateOrder(
                                                    order.copy(
                                                        records = updatedRecs,
                                                        lastEditor = currentUserName,
                                                        updatedAt = System.currentTimeMillis()
                                                    )
                                                );
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF2E7D32))
                                        )
                                        Column {
                                            Text(
                                                text = "🕒 $dateStr",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1E3A5F)
                                            )
                                            Text(
                                                text = "紀錄人：${rec.createdBy} (點卡片可編輯內容)",
                                                fontSize = 11.sp,
                                                color = Color.Gray
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            val newState = !rec.isCompleted;
                                            val updatedRecs = order.records.map {
                                                if (it.id == rec.id) it.copy(isCompleted = newState) else it
                                            };
                                            onUpdateOrder(
                                                order.copy(
                                                    records = updatedRecs,
                                                    lastEditor = currentUserName,
                                                    updatedAt = System.currentTimeMillis()
                                                )
                                            );
                                            val msg = if (newState) "✅ 已標記為「完成訂單」！" else "⏳ 已改回「待處理訂單」";
                                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (rec.isCompleted) Color(0xFF2E7D32) else Color(0xFFFF9800)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "狀態",
                                            modifier = Modifier.size(16.dp),
                                            tint = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (rec.isCompleted) "已完成訂單" else "待處理 (點此完成)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFFE0E0E0))

                                Text(
                                    text = rec.details,
                                    fontSize = 16.sp,
                                    color = if (rec.isCompleted) Color(0xFF4E6E52) else Color(0xFF222222),
                                    textDecoration = if (rec.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (editingRecord != null) {
            val target = editingRecord!!;
            var editDetails by remember(target.id) { mutableStateOf(target.details) };

            AlertDialog(
                onDismissRequest = { editingRecord = null },
                title = { Text("編輯此筆訂貨詳細資料", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(target.timestamp));
                        Text("建立時間：$tStr", fontSize = 12.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = editDetails,
                            onValueChange = { editDetails = it },
                            label = { Text("詳細資料內容") },
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            maxLines = 8
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val updatedRecs = order.records.map {
                                if (it.id == target.id) it.copy(details = editDetails.trim()) else it
                            };
                            onUpdateOrder(
                                order.copy(
                                    records = updatedRecs,
                                    lastEditor = currentUserName,
                                    updatedAt = System.currentTimeMillis()
                                )
                            );
                            editingRecord = null;
                            Toast.makeText(ctx, "✅ 已更新此筆訂貨內容", Toast.LENGTH_SHORT).show();
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                    ) { Text("儲存修改") }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                val updatedRecs = order.records.filterNot { it.id == target.id };
                                onUpdateOrder(
                                    order.copy(
                                        records = updatedRecs,
                                        lastEditor = currentUserName,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                );
                                editingRecord = null;
                                Toast.makeText(ctx, "🗑️ 已刪除此筆訂貨紀錄", Toast.LENGTH_SHORT).show();
                            }
                        ) { Text("刪除此筆訂單", color = Color(0xFFD32F2F)) }
                        TextButton(onClick = { editingRecord = null }) { Text("取消") }
                    }
                }
            )
        }

        if (showDeleteCustomerConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteCustomerConfirm = false },
                title = { Text("確認刪除整位客戶？") },
                text = { Text("確定要刪除「${order.customerName} (#${order.customerNumber})」以及他名下所有的歷史訂貨紀錄嗎？") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteCustomerConfirm = false;
                            onDeleteCustomer(order.id);
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) { Text("確定刪除") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteCustomerConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}
