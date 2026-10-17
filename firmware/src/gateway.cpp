// Control-station LoRa receiver: forwards packets to the LAN backend over Wi-Fi.
#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <SPI.h>
#include <LoRa.h>
#include "config.h"

bool loraOk;

void setup() {
  Serial.begin(115200);
  LoRa.setPins(LORA_SS, LORA_RST, LORA_DIO0);
  loraOk = LoRa.begin(LORA_FREQ);
  Serial.println(loraOk ? "LoRa ready" : "LoRa init failed (check wiring/frequency)");
  WiFi.begin(WIFI_SSID, WIFI_PASS);
}

void loop() {
  if (!loraOk || !LoRa.parsePacket()) return;
  String j = LoRa.readString();
  j.trim();
  if (!j.endsWith("}")) return;  // garbage
  j.remove(j.length() - 1);
  j += ",\"via\":\"lora\",\"rssi\":" + String(LoRa.packetRssi()) + "}";
  Serial.println(j);
  if (WiFi.status() != WL_CONNECTED) return;  // node also queues, so nothing is lost
  HTTPClient h;
  h.setTimeout(3000);
  h.begin(SERVER_URL);
  h.addHeader("Content-Type", "application/json");
  h.POST(j);
  h.end();
}
