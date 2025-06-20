package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.example.Tunnelofen;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.dmn.api.core.*;

public class MqttClientExample {
    public static void main(String[] args) {
        String broker = "tcp://localhost:1883";
        String topic = "tunnelofen/data";
        String clientId = "JavaClient";

        try {
            // 👉 1. KIE & DMN initialisieren (einmalig)
            KieServices kieServices = KieServices.Factory.get();
            KieContainer kieContainer = kieServices.getKieClasspathContainer();
            KieSession kieSession = kieContainer.newKieSession("defaultKieSession");
            DMNRuntime dmnRuntime = kieSession.getKieRuntime(DMNRuntime.class);

            // 👉 2. DMN-Modell laden
            DMNModel dmnModel = dmnRuntime.getModel(
                "https://kie.apache.org/dmn/_96D74048-B4BC-455F-B1F5-42CD79464E0D",
                "tunnelofen"
            );
            if (dmnModel == null) {
                throw new RuntimeException("DMN model not found!");
            }

            // 👉 3. MQTT-Client für eingehende Temperaturdaten
            MqttClient client = new MqttClient(broker, clientId);

            // 👉 4. MQTT-Client für M5Stick (einmalig)
            final MqttClient m5Client = new MqttClient(broker, "JavaToM5Client");
            m5Client.connect();

            // 👉 Shutdown-Hook für sauberen Disconnect
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    m5Client.disconnect();
                    System.out.println("M5Client sauber getrennt.");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));

            // 👉 5. DMN-Runtime & Modell final machen für Callback
            final DMNRuntime finalDmnRuntime = dmnRuntime;
            final DMNModel finalDmnModel = dmnModel;

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.out.println("Connection lost: " + cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    try {
                        System.out.println("Message received: " + new String(message.getPayload()));

                        ObjectMapper mapper = new ObjectMapper();
                        Tunnelofen input = mapper.readValue(message.getPayload(), Tunnelofen.class);

                        DMNContext context = finalDmnRuntime.newContext();
                        context.set("currentTemp", input.getCurrentTemp());

                        DMNResult result = finalDmnRuntime.evaluateAll(finalDmnModel, context);
                        String warningState = (String) result.getContext().get("warningState");

                        System.out.println("DMN-Entscheidung: \n" + warningState);

                        // 👉 Nachricht an M5Stick senden (einmaliger Client)
                        MqttMessage vibMsg = new MqttMessage(warningState.getBytes());
                        m5Client.publish("m5stick/vibration", vibMsg);
                        System.out.println("Vibrationsbefehl an M5Stick gesendet: \n" + warningState);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            client.connect();
            client.subscribe(topic);
            System.out.println("Subscribed to topic: " + topic);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
