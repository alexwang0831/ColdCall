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
import androidx.compose.material.icons.filled.Delete
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

data class Prospect(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val location: String,
    val chatNotes: String = "",
    val lastEditor: String = "我",
    val updatedAt: Long = System.currentTimeMillis()
)

object StorageAndCloudManager {
    private const val PREF = "prospect_tracker_prefs";
    private const val KEY_LIST = "prospects_json";
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

    fun parseJsonToList(jsonStr: String): List<Prospect> {
        val res = mutableListOf<Prospect>();
        try {
            val trimmed = jsonStr.trim();
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed);
                for (i in 0 until arr.length()) {
                    if (!arr.isNull(i)) {
                        val o = arr.getJSONObject(i);
                        res.add(parseObj(o));
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed);
                if (root.has("prospects")) {
                    val arr = root.getJSONArray("prospects");
                    for (i in 0 until arr.length()) {
                        res.add(parseObj(arr.getJSONObject(i)));
                    }
                } else {
                    val keys = root.keys();
                    while (keys.hasNext()) {
                        val k = keys.next();
                        val o = root.optJSONObject(k);
                        if (o != null && o.has("name")) {
                            res.add(parseObj(o));
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace(); }
        return res.sortedByDescending { it.updatedAt };
    }

    private fun parseObj(o: JSONObject): Prospect {
        return Prospect(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "未命名"),
            location = o.optString("location", "未填寫地點"),
            chatNotes = o.optString("chatNotes", ""),
            lastEditor = o.optString("lastEditor", "夥伴"),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        );
    }

    fun createBackupJson(list: List<Prospect>): String {
        val root = JSONObject();
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
        root.put("backupTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()));
        root.put("prospects", arr);
        return root.toString(2);
    }

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
        } catch (e: Exception) {
            e.printStackTrace();
            false
        }
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
        } catch (e: Exception) {
            false
        }
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
        } catch (e: Exception) {
            null
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        setContent {
            MaterialTheme {
                ProspectTrackerApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProspectTrackerApp() {
    val ctx = LocalContext.current;
    val scope = rememberCoroutineScope();

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

    var selectedProspectId by remember { mutableStateOf<String?>(null) };
    var showAddDialog by remember { mutableStateOf(false) };
    var showCloudDialog by remember { mutableStateOf(false) };
    var cloudUrl by remember { mutableStateOf(StorageAndCloudManager.getCloudUrl(ctx)) };
    var userName by remember { mutableStateOf(StorageAndCloudManager.getUserName(ctx)) };
    var syncStatus by remember { mutableStateOf("本地儲存模式") };

    // 定時每 4 秒自動與多人雲端資料庫同步
    LaunchedEffect(cloudUrl) {
        while (isActive) {
            if (cloudUrl.isNotBlank()) {
                val remoteList = StorageAndCloudManager.fetchAllFromCloud(cloudUrl);
                if (remoteList != null) {
                    syncStatus = "🟢 多人雲端即時同步中";
                    if (remoteList.isEmpty() && prospects.isNotEmpty()) {
                        for (p in prospects) {
                            StorageAndCloudManager.pushItemToCloud(cloudUrl, p);
                        }
                    } else {
                        prospects.clear();
                        prospects.addAll(remoteList);
                        StorageAndCloudManager.saveLocal(ctx, prospects);
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
                val json = StorageAndCloudManager.createBackupJson(prospects);
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)); };
                Toast.makeText(ctx, "☁️ 完整名單已成功備份至雲端硬碟！", Toast.LENGTH_LONG).show();
            } catch (e: Exception) {
                Toast.makeText(ctx, "備份失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val content = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "";
                val restored = StorageAndCloudManager.parseJsonToList(content);
                if (restored.isNotEmpty()) {
                    prospects.clear();
                    prospects.addAll(restored);
                    StorageAndCloudManager.saveLocal(ctx, prospects);
                    if (cloudUrl.isNotBlank()) {
                        scope.launch {
                            for (p in restored) StorageAndCloudManager.pushItemToCloud(cloudUrl, p);
                        }
                    }
                    Toast.makeText(ctx, "✅ 成功還原 ${restored.size} 筆名單！", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(ctx, "檔案內沒有有效的名單資料", Toast.LENGTH_LONG).show();
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "讀取失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

    val currentSelected = prospects.find { it.id == selectedProspectId };

    if (currentSelected != null) {
        BackHandler { selectedProspectId = null; }
        ProspectDetailScreen(
            prospect = currentSelected,
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
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("陌生開發名單 (${prospects.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(syncStatus, fontSize = 11.sp, color = Color(0xFFD0E8FF))
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
                            Text("☁️ 雲端與多人", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E3A5F), titleContentColor = Color.White)
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
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

            if (showAddDialog) {
                var newName by remember { mutableStateOf("") };
                var newLoc by remember { mutableStateOf("") };
                var newNote by remember { mutableStateOf("") };

                AlertDialog(
                    onDismissRequest = { showAddDialog = false },
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
                                    showAddDialog = false;
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06C755))
                        ) { Text("新增並備份") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddDialog = false }) { Text("取消") }
                    }
                )
            }

            if (showCloudDialog) {
                var inputUrl by remember { mutableStateOf(cloudUrl) };
                var inputNick by remember { mutableStateOf(userName) };

                AlertDialog(
                    onDismissRequest = { showCloudDialog = false },
                    title = { Text("☁️ 雲端備份 & 多人協作設定", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("【功能 1：多人即時共同編輯】\n貼上 Firebase Realtime Database 網址（團隊成員貼上相同網址即可即時同步所有名單與對話）：", fontSize = 13.sp, color = Color.DarkGray)
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
                                label = { Text("雲端資料庫網址 (https://xxx.firebaseio.com)") },
                                placeholder = { Text("留空則僅使用手機本地儲存") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    StorageAndCloudManager.setCloudConfig(ctx, inputUrl, inputNick);
                                    cloudUrl = StorageAndCloudManager.getCloudUrl(ctx);
                                    userName = StorageAndCloudManager.getUserName(ctx);
                                    showCloudDialog = false;
                                    Toast.makeText(ctx, "已更新設定！", Toast.LENGTH_SHORT).show();
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
                                    exportLauncher.launch("陌生開發名單備份_$dateTag.json");
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) { Text("☁️ 匯出完整名單 (.json) 至 Google 雲端") }

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
    }
}

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

            // 快速新增一筆帶時間與發言人名稱的聊天記錄
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
