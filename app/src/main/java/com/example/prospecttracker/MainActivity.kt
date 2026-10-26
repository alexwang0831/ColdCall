package com.example.chatledger

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

data class ExpenseItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Int,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timeString: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
)

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val channelId = "ledger_reminder_channel";
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "每日記帳提醒", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK;
        };
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE;
        val pi = PendingIntent.getActivity(context, 0, openIntent, piFlags);
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("💰 安麗隨身助理提醒")
            .setContentText("今天花了哪些錢呢？點我隨手記下來吧！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();
        nm.notify(1001, notification);
        val saved = LocalStorage.loadReminderTime(context);
        if (saved != null) {
            ReminderScheduler.scheduleDailyReminder(context, saved.first, saved.second);
        }
    }
}

object ReminderScheduler {
    fun scheduleDailyReminder(context: Context, hour: Int, minute: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager;
        val intent = Intent(context, ReminderReceiver::class.java);
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE;
        val pi = PendingIntent.getBroadcast(context, 2001, intent, flags);
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour);
            set(Calendar.MINUTE, minute);
            set(Calendar.SECOND, 0);
            set(Calendar.MILLISECOND, 0);
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi);
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi);
        }
    }

    fun cancelReminder(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager;
        val intent = Intent(context, ReminderReceiver::class.java);
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE;
        val pi = PendingIntent.getBroadcast(context, 2001, intent, flags);
        am.cancel(pi);
    }
}

object LocalStorage {
    private const val PREF = "amway_assistant_data";
    private const val KEY_EXP = "expenses_json";
    private const val KEY_MSG = "messages_json";
    private const val KEY_H = "reminder_hour";
    private const val KEY_M = "reminder_min";

    fun saveReminderTime(ctx: Context, h: Int, m: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_H, h).putInt(KEY_M, m).apply();
    }

    fun clearReminderTime(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_H).remove(KEY_M).apply();
    }

    fun loadReminderTime(ctx: Context): Pair<Int, Int>? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!p.contains(KEY_H)) return null;
        return Pair(p.getInt(KEY_H, 21), p.getInt(KEY_M, 0));
    }

    fun saveExpenses(ctx: Context, list: List<ExpenseItem>) {
        val arr = JSONArray();
        for (it in list) {
            val o = JSONObject();
            o.put("id", it.id);
            o.put("name", it.name);
            o.put("amount", it.amount);
            o.put("category", it.category);
            o.put("timestamp", it.timestamp);
            arr.put(o);
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_EXP, arr.toString()).apply();
    }

    fun loadExpenses(ctx: Context): List<ExpenseItem> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_EXP, null) ?: return emptyList();
        val res = mutableListOf<ExpenseItem>();
        try {
            val arr = JSONArray(s);
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i);
                res.add(ExpenseItem(o.getString("id"), o.getString("name"), o.getInt("amount"), o.getString("category"), o.optLong("timestamp", 0L)));
            }
        } catch (e: Exception) { e.printStackTrace(); }
        return res;
    }

    fun saveMessages(ctx: Context, list: List<ChatMessage>) {
        val arr = JSONArray();
        for (it in list) {
            val o = JSONObject();
            o.put("id", it.id);
            o.put("text", it.text);
            o.put("isUser", it.isUser);
            o.put("timeString", it.timeString);
            arr.put(o);
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_MSG, arr.toString()).apply();
    }

    fun loadMessages(ctx: Context): List<ChatMessage> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MSG, null) ?: return emptyList();
        val res = mutableListOf<ChatMessage>();
        try {
            val arr = JSONArray(s);
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i);
                res.add(ChatMessage(o.getString("id"), o.getString("text"), o.getBoolean("isUser"), o.getString("timeString")));
            }
        } catch (e: Exception) { e.printStackTrace(); }
        return res;
    }

    fun createBackupJson(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        val root = JSONObject();
        root.put("version", 1);
        root.put("backupDate", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()));
        root.put("expenses", JSONArray(p.getString(KEY_EXP, "[]")));
        root.put("messages", JSONArray(p.getString(KEY_MSG, "[]")));
        if (p.contains(KEY_H)) {
            root.put("reminder_hour", p.getInt(KEY_H, 21));
            root.put("reminder_min", p.getInt(KEY_M, 0));
        }
        return root.toString(2);
    }

    fun restoreFromBackupJson(ctx: Context, jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr);
            val expArr = root.getJSONArray("expenses");
            val msgArr = root.getJSONArray("messages");
            val editor = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
            editor.putString(KEY_EXP, expArr.toString());
            editor.putString(KEY_MSG, msgArr.toString());
            if (root.has("reminder_hour")) {
                val h = root.getInt("reminder_hour");
                val m = root.optInt("reminder_min", 0);
                editor.putInt(KEY_H, h);
                editor.putInt(KEY_M, m);
                ReminderScheduler.scheduleDailyReminder(ctx, h, m);
            }
            editor.commit();
            true
        } catch (e: Exception) {
            e.printStackTrace();
            false
        }
    }

    fun createCsvContent(expenses: List<ExpenseItem>): String {
        val sb = StringBuilder("\uFEFF日期時間,類別,項目名稱,金額\n");
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (item in expenses) {
            val dateStr = sdf.format(Date(if (item.timestamp > 0) item.timestamp else System.currentTimeMillis()));
            val safeName = item.name.replace(",", " ");
            sb.append("$dateStr,${item.category},$safeName,${item.amount}\n");
        }
        return sb.toString();
    }
}

object ExpenseParser {
    private val map = mapOf(
        "飲食" to listOf("早餐", "午餐", "晚餐", "宵夜", "飲料", "咖啡", "便當", "聚餐", "吃飯", "零食", "下午茶", "水", "麵包", "麵", "飯", "火鍋", "手搖"),
        "交通" to listOf("加油", "捷運", "公車", "計程車", "高鐵", "火車", "台鐵", "停車", "機油", "過路費", "eTag", "悠遊卡", "保養", "洗車"),
        "日常與居家" to listOf("超市", "全聯", "日用品", "衛生紙", "水費", "電費", "瓦斯", "網路", "電話費", "房租", "管理費", "洗髮精", "清潔"),
        "休閒與嗜好" to listOf("電影", "遊戲", "書", "訂閱", "門票", "展覽", "植物", "飼料", "器材", "玩具", "購物")
    );
    private val regex = Regex("""([^\d\s,，、；;]+)\s*(\d+)\s*元?""");

    fun parseInput(input: String): List<ExpenseItem> {
        val res = mutableListOf<ExpenseItem>();
        for (m in regex.findAll(input)) {
            val name = m.groupValues[1].trim();
            val amt = m.groupValues[2].toIntOrNull() ?: 0;
            if (name.isNotEmpty() && amt > 0) {
                val cat = map.entries.firstOrNull { e -> e.value.any { name.contains(it, true) } }?.key ?: "其他";
                res.add(ExpenseItem(name = name, amount = amt, category = cat));
            }
        }
        return res;
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        setContent { MaterialTheme { ChatLedgerScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatLedgerScreen() {
    val ctx = LocalContext.current;
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { };

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    val messages = remember {
        val saved = LocalStorage.loadMessages(ctx);
        if (saved.isNotEmpty()) mutableStateListOf(*saved.toTypedArray())
        else mutableStateListOf(ChatMessage(text = "歡迎使用安麗隨身助理！\n• 記帳：早餐20元，加油100元\n• 雲端備份：點右上角 ☁️ 或輸入「備份」/「還原」\n• 提醒：點 ⏰ 或輸入「提醒 21:00」", isUser = false))
    };
    val expenses = remember { mutableStateListOf(*LocalStorage.loadExpenses(ctx).toTypedArray()) };
    var reminderTime by remember { mutableStateOf(LocalStorage.loadReminderTime(ctx)) };
    var showReminderDialog by remember { mutableStateOf(false) };
    var showCloudDialog by remember { mutableStateOf(false) };
    var inputText by remember { mutableStateOf("") };
    var showStatsSheet by remember { mutableStateOf(false) };
    val listState = rememberLazyListState();
    val scope = rememberCoroutineScope();

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                val jsonStr = LocalStorage.createBackupJson(ctx);
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(jsonStr.toByteArray(Charsets.UTF_8)) };
                messages.add(ChatMessage(text = "☁️ 備份成功！已將完整帳目與對話紀錄存至您的雲端硬碟。", isUser = false));
                LocalStorage.saveMessages(ctx, messages);
                scope.launch { listState.animateScrollToItem(messages.size - 1); }
            } catch (e: Exception) {
                Toast.makeText(ctx, "備份失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val csvStr = LocalStorage.createCsvContent(expenses);
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(csvStr.toByteArray(Charsets.UTF_8)) };
                messages.add(ChatMessage(text = "📊 匯出 CSV 成功！您可以在 Google 試算表打開查看。", isUser = false));
                LocalStorage.saveMessages(ctx, messages);
                scope.launch { listState.animateScrollToItem(messages.size - 1); }
            } catch (e: Exception) {
                Toast.makeText(ctx, "匯出失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

    val importJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val content = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "";
                if (LocalStorage.restoreFromBackupJson(ctx, content)) {
                    expenses.clear();
                    expenses.addAll(LocalStorage.loadExpenses(ctx));
                    messages.clear();
                    messages.addAll(LocalStorage.loadMessages(ctx));
                    reminderTime = LocalStorage.loadReminderTime(ctx);
                    messages.add(ChatMessage(text = "✅ 雲端還原成功！已為您恢復共 ${expenses.size} 筆紀錄。", isUser = false));
                    LocalStorage.saveMessages(ctx, messages);
                    scope.launch { listState.animateScrollToItem(messages.size - 1); }
                } else {
                    Toast.makeText(ctx, "檔案格式不正確，無法還原！", Toast.LENGTH_LONG).show();
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "讀取失敗：${e.message}", Toast.LENGTH_LONG).show();
            }
        }
    };

    LaunchedEffect(Unit) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1);
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安麗隨身助理", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                actions = {
                    TextButton(onClick = { showCloudDialog = true }) {
                        Text("☁️ 雲端", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp);
                    }
                    val label = reminderTime?.let { String.format(Locale.getDefault(), "⏰ %02d:%02d", it.first, it.second) } ?: "⏰ 提醒";
                    TextButton(onClick = { showReminderDialog = true }) {
                        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp);
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2B3648), titleContentColor = Color.White)
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).navigationBarsPadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("早餐20元，或輸入 備份 / 還原") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val raw = inputText.trim();
                                inputText = "";
                                messages.add(ChatMessage(text = raw, isUser = true));
                                val remMatch = Regex("""提醒\s*(\d{1,2})[:：點](\d{1,2})?""").find(raw);
                                if (raw == "備份" || raw == "雲端備份") {
                                    val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date());
                                    exportJsonLauncher.launch("安麗隨身助理備份_$dateTag.json");
                                } else if (raw == "還原" || raw == "匯入") {
                                    importJsonLauncher.launch(arrayOf("application/json", "*/*"));
                                } else if (remMatch != null) {
                                    val h = remMatch.groupValues[1].toIntOrNull() ?: -1;
                                    val m = remMatch.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: 0;
                                    if (h in 0..23 && m in 0..59) {
                                        LocalStorage.saveReminderTime(ctx, h, m);
                                        ReminderScheduler.scheduleDailyReminder(ctx, h, m);
                                        reminderTime = Pair(h, m);
                                        val tf = String.format(Locale.getDefault(), "%02d:%02d", h, m);
                                        messages.add(ChatMessage(text = "⏰ 已設定每日提醒：每天 $tf 準時通知您！", isUser = false));
                                    } else {
                                        messages.add(ChatMessage(text = "⚠️ 時間格式錯誤，請輸入 00:00 ~ 23:59。", isUser = false));
                                    }
                                } else if (raw == "關閉提醒" || raw == "取消提醒") {
                                    LocalStorage.clearReminderTime(ctx);
                                    ReminderScheduler.cancelReminder(ctx);
                                    reminderTime = null;
                                    messages.add(ChatMessage(text = "🔕 已關閉每日記帳提醒。", isUser = false));
                                } else if (raw == "刪除" || raw == "收回") {
                                    if (expenses.isNotEmpty()) {
                                        val r = expenses.removeAt(expenses.size - 1);
                                        messages.add(ChatMessage(text = "🗑️ 已刪除最後一筆：【${r.category}】${r.name} ${r.amount}元", isUser = false));
                                    } else {
                                        messages.add(ChatMessage(text = "目前沒有支出紀錄可刪除喔！", isUser = false));
                                    }
                                } else if (raw == "清空" || raw == "重置") {
                                    expenses.clear();
                                    messages.clear();
                                    messages.add(ChatMessage(text = "🧹 已清空所有帳目與對話紀錄！", isUser = false));
                                } else {
                                    val parsed = ExpenseParser.parseInput(raw);
                                    if (parsed.isNotEmpty()) {
                                        expenses.addAll(parsed);
                                        val sb = StringBuilder("✅ 已自動分類並存檔：\n");
                                        parsed.groupBy { it.category }.forEach { (cat, items) ->
                                            val d = items.joinToString("、") { "${it.name} ${it.amount}元" };
                                            sb.append("• 【$cat】新增：$d (小計 ${items.sumOf { it.amount }} 元)\n");
                                        }
                                        messages.add(ChatMessage(text = sb.toString().trim(), isUser = false));
                                    } else {
                                        messages.add(ChatMessage(text = "⚠️ 請輸入「項目+金額」（如：早餐20元），或輸入「備份」、「提醒 21:00」。", isUser = false));
                                    }
                                }
                                LocalStorage.saveExpenses(ctx, expenses);
                                LocalStorage.saveMessages(ctx, messages);
                                scope.launch { listState.animateScrollToItem(messages.size - 1); }
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06C755))
                    ) { Text("傳送", fontWeight = FontWeight.Bold) }
                }
                val totalAmount = expenses.sumOf { it.amount };
                Button(
                    onClick = { showStatsSheet = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3648))
                ) {
                    Text("📊 查看支出統計報表（目前總計：$totalAmount 元）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(Color(0xFF8CABD9)).padding(innerPadding).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(msg)
            }
        }

        if (showCloudDialog) {
            AlertDialog(
                onDismissRequest = { showCloudDialog = false },
                title = { Text("☁️ Google 雲端硬碟備份 / 還原", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("請選擇操作項目（跳出系統選單時，點選左上角選單「☰」即可選擇您的 Google 雲端硬碟）：", fontSize = 14.sp, color = Color.DarkGray)
                        Button(
                            onClick = {
                                showCloudDialog = false;
                                val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date());
                                exportJsonLauncher.launch("安麗隨身助理備份_$dateTag.json");
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                        ) { Text("☁️ 匯出完整備份檔 (.json) 至雲端") }

                        Button(
                            onClick = {
                                showCloudDialog = false;
                                importJsonLauncher.launch(arrayOf("application/json", "*/*"));
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06C755))
                        ) { Text("📥 從雲端硬碟讀取備份檔還原") }

                        OutlinedButton(
                            onClick = {
                                showCloudDialog = false;
                                val dateTag = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date());
                                exportCsvLauncher.launch("安麗隨身助理報表_$dateTag.csv");
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("📊 匯出 Excel / Google 試算表 (.csv)") }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showCloudDialog = false }) { Text("關閉") }
                }
            )
        }

        if (showReminderDialog) {
            AlertDialog(
                onDismissRequest = { showReminderDialog = false },
                title = { Text("⏰ 每日記帳提醒設定", fontWeight = FontWeight.Bold) },
                text = {
                    val st = reminderTime?.let { String.format(Locale.getDefault(), "目前已設定於每天 %02d:%02d 提醒您。", it.first, it.second) }
                        ?: "尚未開啟提醒。\n（也可在對話框輸入「提醒 21:00」設定）";
                    Text(st, fontSize = 15.sp)
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showReminderDialog = false;
                            val ch = reminderTime?.first ?: 21;
                            val cm = reminderTime?.second ?: 0;
                            TimePickerDialog(ctx, { _, sh, sm ->
                                LocalStorage.saveReminderTime(ctx, sh, sm);
                                ReminderScheduler.scheduleDailyReminder(ctx, sh, sm);
                                reminderTime = Pair(sh, sm);
                                val tf = String.format(Locale.getDefault(), "%02d:%02d", sh, sm);
                                messages.add(ChatMessage(text = "⏰ 已設定每日提醒：每天 $tf 準時通知您！", isUser = false));
                                LocalStorage.saveMessages(ctx, messages);
                                scope.launch { listState.animateScrollToItem(messages.size - 1); }
                            }, ch, cm, true).show();
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06C755))
                    ) { Text("選擇時間") }
                },
                dismissButton = {
                    Row {
                        if (reminderTime != null) {
                            TextButton(onClick = {
                                LocalStorage.clearReminderTime(ctx);
                                ReminderScheduler.cancelReminder(ctx);
                                reminderTime = null;
                                showReminderDialog = false;
                                messages.add(ChatMessage(text = "🔕 已關閉每日記帳提醒。", isUser = false));
                                LocalStorage.saveMessages(ctx, messages);
                            }) { Text("關閉提醒", color = Color(0xFFD32F2F)) }
                        }
                        TextButton(onClick = { showReminderDialog = false }) { Text("取消") }
                    }
                }
            )
        }

        if (showStatsSheet) {
            ModalBottomSheet(onDismissRequest = { showStatsSheet = false }, containerColor = Color.White) {
                ExpenseStatsSheet(expenses)
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (msg.isUser) {
            Text(msg.timeString, fontSize = 11.sp, color = Color(0xFFEEEEEE), modifier = Modifier.padding(end = 6.dp, bottom = 2.dp))
        }
        Surface(
            color = if (msg.isUser) Color(0xFF85E249) else Color.White,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(msg.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 15.sp, color = Color.Black)
        }
        if (!msg.isUser) {
            Text(msg.timeString, fontSize = 11.sp, color = Color(0xFFEEEEEE), modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        }
    }
}

@Composable
fun ExpenseStatsSheet(expenses: List<ExpenseItem>) {
    val total = expenses.sumOf { it.amount };
    val grouped = expenses.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.toList().sortedByDescending { it.second };

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text("支出統計總覽", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2B3648))
        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4F8)), shape = RoundedCornerShape(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("總支出金額", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("NT$ $total 元", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD32F2F))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (total == 0) {
            Text("目前尚無任何支出紀錄，快去對話框輸入第一筆帳目吧！", color = Color.Gray, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            Text("各類別支出與佔比：", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 380.dp)) {
                items(grouped) { pair ->
                    val cat = pair.first;
                    val catTotal = pair.second;
                    val pct = (catTotal.toFloat() / total.toFloat()) * 100f;
                    val pctStr = String.format(Locale.getDefault(), "%.1f%%", pct);
                    val detail = expenses.filter { it.category == cat }.joinToString("、") { "${it.name} ${it.amount}元" };

                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFFAFAFA)).padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("【$cat】", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("$catTotal 元 ($pctStr)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1976D2))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF06C755),
                            trackColor = Color(0xFFE0E0E0)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("細項：$detail", fontSize = 13.sp, color = Color.DarkGray)
                    }
                }
            }
        }
    }
}
