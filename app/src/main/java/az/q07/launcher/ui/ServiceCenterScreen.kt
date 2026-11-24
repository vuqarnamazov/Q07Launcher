package az.q07.launcher.ui

import az.q07.launcher.data.*

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ServiceCenterScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var services by remember { mutableStateOf(ServiceBookingManager.getServices(context)) }
    var selectedService by remember { mutableStateOf(services.firstOrNull()) }

    val saved = remember { ServiceBookingManager.getSavedCustomerProfile(context) }
    var customerName by remember { mutableStateOf(saved.customerName) }
    var customerPhone by remember { mutableStateOf(saved.customerPhone) }
    var carPlate by remember { mutableStateOf(saved.carPlate.ifBlank { VehiclePlateStorage.getPlate(context) }) }
    // SMS yalnız istifadəçi təsdiqindən sonra göndərilir
    var pendingSms by remember { mutableStateOf<VehicleServiceItem?>(null) }
    pendingSms?.let { srv ->
        AlertDialog(
            onDismissRequest = { pendingSms = null },
            title = { Text("SMS göndərilsin?") },
            text = {
                Text(
                    "Qəbul edən: ${srv.phoneNumber}\n\n" +
                    ServiceBookingManager.buildRequestMessage(srv, CustomerProfile(customerName, customerPhone, carPlate))
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingSms = null
                    ServiceBookingManager.sendViaSms(context, srv, CustomerProfile(customerName, customerPhone, carPlate))
                }) { Text("Göndər") }
            },
            dismissButton = { TextButton(onClick = { pendingSms = null }) { Text("Ləğv et") } }
        )
    }

    var isEditMode by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val synced = ServiceBookingManager.syncServicesFromGitHub(context)
        services = synced
        if (selectedService == null) selectedService = synced.firstOrNull()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF1F5F9)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Q07 Servis və Texniki Xidmətlər", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Text("Xidməti seçin, ad və nömrənizi yazın — dərhal məsul şəxsə bildiriş getsin", fontSize = 13.sp, color = Color(0xFF475569))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { isEditMode = !isEditMode }) {
                    Text(if (isEditMode) "Redaktəni Bitir" else "Nömrələri Dəyiş (Admin)")
                }
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) {
                    Text("Bağla", color = Color.White)
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("1. Xidmət Növünü Seçin", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7), modifier = Modifier.padding(bottom = 10.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        items(services) { item ->
                            val isSelected = selectedService?.id == item.id
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFFE0F2FE) else Color(0xFFF8FAFC)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().clickable { selectedService = item }
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                        if (!isEditMode) Text(item.phoneNumber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0284C7))
                                    }
                                    if (item.subtitle.isNotBlank()) Text(item.subtitle, fontSize = 12.sp, color = Color(0xFF64748B))
                                    if (isEditMode) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = item.phoneNumber,
                                            onValueChange = { updated ->
                                                services = services.map { if (it.id == item.id) it.copy(phoneNumber = updated) else it }
                                                ServiceBookingManager.saveServices(context, services)
                                                if (selectedService?.id == item.id) selectedService = item.copy(phoneNumber = updated)
                                            },
                                            label = { Text("Məsul şəxsin nömrəsi (+994...)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isEditMode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, placeholder = { Text("Yeni xidmət adı") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = newPhone, onValueChange = { newPhone = it }, placeholder = { Text("+994...") }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = {
                                if (newTitle.isNotBlank() && newPhone.isNotBlank()) {
                                    services = services + VehicleServiceItem("srv_${System.currentTimeMillis()}", newTitle.trim(), "Əlavə servis xidməti", newPhone.trim())
                                    ServiceBookingManager.saveServices(context, services)
                                    newTitle = ""; newPhone = ""
                                }
                            }) { Text("Əlavə Et") }
                        }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(0.85f).fillMaxHeight()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("2. Məlumatlarınızı Daxil Edin", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
                    Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFE0F2FE), RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Column {
                            Text("Seçilmiş xidmət:", fontSize = 12.sp, color = Color(0xFF0369A1))
                            Text(selectedService?.title ?: "Seçilməyib", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text("Qəbul edən nömrə: ${selectedService?.phoneNumber ?: "-"}", fontSize = 13.sp, color = Color(0xFF0284C7))
                        }
                    }

                    OutlinedTextField(value = customerName, onValueChange = { customerName = it }, label = { Text("Adınız (məs: Vüqar)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = customerPhone, onValueChange = { customerPhone = it }, label = { Text("Mobil nömrəniz (məs: 050 123 45 67)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = carPlate, onValueChange = { carPlate = it.uppercase() }, label = { Text("Maşının nömrəsi (məs: 99-VR-007)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = {
                            val srv = selectedService
                            if (srv == null || customerName.isBlank() || customerPhone.isBlank() || carPlate.isBlank()) {
                                Toast.makeText(context, "Zəhmət olmasa bütün xanaları doldurun!", Toast.LENGTH_SHORT).show()
                            } else {
                                pendingSms = srv
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("SMS ilə Müraciət Göndər", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }

                    Button(
                        onClick = {
                            val srv = selectedService
                            if (srv == null || customerName.isBlank() || customerPhone.isBlank() || carPlate.isBlank()) {
                                Toast.makeText(context, "Zəhmət olmasa bütün xanaları doldurun!", Toast.LENGTH_SHORT).show()
                            } else {
                                ServiceBookingManager.sendViaWhatsApp(context, srv, CustomerProfile(customerName, customerPhone, carPlate))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("WhatsApp ilə Dərhal Göndər", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
        }
    }
}
