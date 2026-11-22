package de.chris.mamafon.webrtc

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID

class MqttSignalingClient(
    private val sessionId: String,
    private val isHost: Boolean,
    private val onMessageReceived: (JSONObject) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    private val brokerUrl = "wss://broker.hivemq.com:8884/mqtt"
    private val clientId = "MamaFon_${UUID.randomUUID().toString().take(8)}"
    private var client: MqttAsyncClient? = null

    private val myRole = if (isHost) "host" else "viewer"
    private val peerRole = if (isHost) "viewer" else "host"

    private val publishTopic = "mamafon/v1/$sessionId/$myRole"
    private val subscribeTopic = "mamafon/v1/$sessionId/$peerRole"

    fun connect() {
        try {
            client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 20
                isAutomaticReconnect = true
            }

            client?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    onStatusChanged("Signaling verbunden – Warte auf Gegenstelle...")
                    client?.subscribe(subscribeTopic, 1)
                    if (!isHost) {
                        sendMessage(JSONObject().put("type", "JOIN"))
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    onStatusChanged("Signaling getrennt: ${cause?.localizedMessage ?: "Unbekannt"}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.toString()?.let { payload ->
                        try {
                            onMessageReceived(JSONObject(payload))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            client?.connect(options)
        } catch (e: Exception) {
            onStatusChanged("Signaling-Fehler: ${e.localizedMessage}")
        }
    }

    fun sendMessage(json: JSONObject) {
        try {
            if (client?.isConnected == true) {
                val msg = MqttMessage(json.toString().toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                client?.publish(publishTopic, msg)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        try {
            if (client?.isConnected == true) {
                client?.disconnect()
            }
            client?.close()
        } catch (_: Exception) {}
    }
}