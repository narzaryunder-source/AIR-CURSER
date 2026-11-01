package com.aircursor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aircursor.pro.ProAccess
import com.aircursor.pro.ProConfig
import com.aircursor.pro.ProStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cloud PC Pro: plan, UPI payment, licence key, and (hidden) owner tools to sell keys. */
@Composable
fun ProScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val config = remember(version) { ProAccess.config(context) }
    val status = remember(version) { ProAccess.status(context) }
    val code = remember { ProAccess.deviceCode(context) }
    var key by remember { mutableStateOf("") }
    var ownerTaps by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ProAccess.refresh(context) }
        version++
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(
                "⭐ Cloud PC Pro",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { ownerTaps++ },
            )
            Text(
                "Pro me: Cloud PC one-tap start (icon + quick tile + app), PC Mode aur WhatsApp support. " +
                    "Hand cursor aur Repair hamesha FREE rahenge.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            StatusCard(status)
            Spacer(Modifier.height(12.dp))

            if (config.enabled) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Plans", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("• Monthly: ₹${config.priceMonthly} / 30 din")
                        Text("• Yearly: ₹${config.priceYearly} / 365 din (sasta)")
                        Spacer(Modifier.height(8.dp))
                        Text("Aapka phone code (key isi phone par chalegi):")
                        Row {
                            Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { copy(context, code) }) { Text("Copy") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Step 1: UPI se pay karo", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = config.upiId.isNotEmpty(),
                                onClick = { payUpi(context, config, config.priceMonthly, "monthly", code) },
                                modifier = Modifier.weight(1f),
                            ) { Text("₹${config.priceMonthly}") }
                            Button(
                                enabled = config.upiId.isNotEmpty(),
                                onClick = { payUpi(context, config, config.priceYearly, "yearly", code) },
                                modifier = Modifier.weight(1f),
                            ) { Text("₹${config.priceYearly}") }
                        }
                        Text("Step 2: payment screenshot + phone code WhatsApp karo", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            enabled = config.whatsapp.isNotEmpty(),
                            onClick = {
                                val msg = "Cloud PC Pro payment done. Phone code: $code"
                                open(context, "https://wa.me/${config.whatsapp}?text=" + Uri.encode(msg))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("WhatsApp par bhejo") }
                        Text("Step 3: jo key mile (CPC1-…) yahan paste karo", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = { Text("License key") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        Button(
                            onClick = {
                                val err = ProAccess.activate(context, key)
                                Toast.makeText(context, err ?: "✅ Pro active! Shukriya", Toast.LENGTH_LONG).show()
                                if (err == null) key = ""
                                version++
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Activate") }
                    }
                }
            } else {
                Text(
                    "Abhi sab features FREE hain. 🎉",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (ownerTaps >= 7) {
                Spacer(Modifier.height(16.dp))
                OwnerTools(context)
            } else {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Payment UPI se seedha seller ko jaata hai. Key aapke phone code se bandhi hoti hai, " +
                        "isliye kisi aur phone par nahi chalegi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: ProStatus) {
    val (title, line, good) = when (status) {
        ProStatus.Free -> Triple("FREE", "Owner ne abhi Pro shuru nahi kiya – sab kuch free", true)
        is ProStatus.Trial -> Triple("FREE TRIAL", "${status.daysLeft} din baaki", true)
        is ProStatus.Active -> Triple("PRO ACTIVE", "${status.daysLeft} din baaki (${status.plan})", true)
        is ProStatus.Locked -> Triple("LOCKED", status.reason + " – plan lo", false)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (good) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(line)
        }
    }
}

/** Only for the seller: make the seller key once, then make a key for each customer. */
@Composable
private fun OwnerTools(context: Context) {
    var v by remember { mutableIntStateOf(0) }
    val pub = remember(v) { ProAccess.sellerPublic(context) }
    var customer by remember { mutableStateOf("") }
    var issued by remember { mutableStateOf("") }
    var restore by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("🔑 Owner tools (sirf seller ke liye)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (pub == null) {
                Text("1) Ek baar seller key banao. Iska backup zaroor rakho.")
                Button(onClick = {
                    ProAccess.createSellerKeys(context)
                    v++
                }, modifier = Modifier.fillMaxWidth()) { Text("Create seller key") }
                OutlinedTextField(
                    value = restore,
                    onValueChange = { restore = it },
                    label = { Text("Ya backup paste karo (CPCSELLER1:…)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    val ok = ProAccess.restoreSellerKey(context, restore)
                    Toast.makeText(context, if (ok) "Restored" else "Backup galat hai", Toast.LENGTH_SHORT).show()
                    v++
                }, modifier = Modifier.fillMaxWidth()) { Text("Restore backup") }
            } else {
                Text("Public key (GitHub par cloudpc/pro.json me \"publicKey\" me paste karo):")
                Text(pub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(context, pub) }, modifier = Modifier.weight(1f)) { Text("Copy public key") }
                    OutlinedButton(
                        onClick = { ProAccess.sellerBackup(context)?.let { copy(context, it) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy BACKUP") }
                }
                Text(
                    "BACKUP secret hai – kisi ko mat do, sirf apne Google Drive/notes me rakho. " +
                        "App uninstall hua to isi se wapas aayega.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
                Text("2) Customer ki key banao (payment check karne ke baad):", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = customer,
                    onValueChange = { customer = it },
                    label = { Text("Customer phone code (XXXX-XXXX)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        issued = ProAccess.issueKey(context, customer, 30, "monthly") ?: ""
                    }, modifier = Modifier.weight(1f)) { Text("30 din") }
                    Button(onClick = {
                        issued = ProAccess.issueKey(context, customer, 365, "yearly") ?: ""
                    }, modifier = Modifier.weight(1f)) { Text("365 din") }
                }
                if (issued.isNotEmpty()) {
                    Text(issued, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copy(context, issued) }, modifier = Modifier.weight(1f)) { Text("Copy key") }
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                                Intent.EXTRA_TEXT,
                                "Aapki Cloud PC Pro key (AirCursor → Cloud PC Pro → Activate me paste karo):\n$issued",
                            )
                            context.startActivity(Intent.createChooser(send, "Key bhejo"))
                        }, modifier = Modifier.weight(1f)) { Text("Share") }
                    }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Cloud PC", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "Koi app nahi mila", Toast.LENGTH_SHORT).show()
    }
}

private fun payUpi(context: Context, cfg: ProConfig, amount: Int, plan: String, code: String) {
    val uri = Uri.Builder().scheme("upi").authority("pay")
        .appendQueryParameter("pa", cfg.upiId)
        .appendQueryParameter("pn", cfg.payeeName)
        .appendQueryParameter("am", "$amount.00")
        .appendQueryParameter("cu", "INR")
        .appendQueryParameter("tn", "Cloud PC $plan $code")
        .build()
    try {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "UPI app chuno"))
    } catch (e: Exception) {
        Toast.makeText(context, "Koi UPI app nahi mila (GPay / PhonePe / Paytm)", Toast.LENGTH_LONG).show()
    }
}
