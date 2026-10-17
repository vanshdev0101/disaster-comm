// Sensor node: Wi-Fi first; on failure send over LoRa and queue to flash for replay.
#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <LittleFS.h>
#include <SPI.h>
#include <LoRa.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include "config.h"

#define BACKLOG "/backlog.jsonl"
#define BACKLOG_MAX 200  // lines; oldest dropped beyond this

OneWire ow(PIN_DS18B20);
DallasTemperature ds(&ow);
uint32_t bootId, seq;
bool loraOk;  // false if the radio didn't init: never call into LoRa, endPacket() would block forever

String readingJson() {
  digitalWrite(PIN_TRIG, LOW); delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH); delayMicroseconds(10); digitalWrite(PIN_TRIG, LOW);
  long us = pulseIn(PIN_ECHO, HIGH, 30000);
  int level = us ? max(0L, MOUNT_CM - us / 58) : -1;  // -1 = no echo
  ds.requestTemperatures();
  int rain = constrain(map(analogRead(PIN_RAIN), 4095, RAIN_WET_RAW, 0, 100), 0, 100);
  char b[176];
  snprintf(b, sizeof b,
    "{\"node\":\"%s\",\"boot\":%u,\"seq\":%u,\"level_cm\":%d,\"rain\":%d,\"temp_c\":%.1f,\"smoke\":%d,\"flame\":%d,\"up\":%lu}",
    NODE_ID, bootId, ++seq, level, rain, ds.getTempCByIndex(0), HAVE_SMOKE ? analogRead(PIN_SMOKE) : -1,
    digitalRead(PIN_FLAME) == LOW, (unsigned long)millis());
  return b;
}

bool post(const String &j) {
  if (WiFi.status() != WL_CONNECTED) return false;
  HTTPClient h;
  h.setTimeout(3000);
  h.begin(SERVER_URL);
  h.addHeader("Content-Type", "application/json");
  int c = h.POST(j);
  h.end();
  return c == 200;
}

void queue(const String &j) {
  File f = LittleFS.open(BACKLOG, "r");
  String all;
  int n = 0;
  while (f && f.available()) { String l = f.readStringUntil('\n'); if (l.length()) { all += l + "\n"; n++; } }
  if (f) f.close();
  all += j + "\n"; n++;
  while (n > BACKLOG_MAX) { all.remove(0, all.indexOf('\n') + 1); n--; }
  f = LittleFS.open(BACKLOG, "w"); f.print(all); f.close();
}

// A queued line carries "up" (uptime when measured). On replay, tell the server how old it is so it keeps the measurement time.
// Only valid within the same boot; lines from an earlier boot are sent as-is (server falls back to arrival time).
String withAge(String l) {
  int i = l.indexOf("\"up\":");
  if (i < 0 || l.indexOf("\"boot\":" + String(bootId) + ",") < 0) return l;
  long age = (long)millis() - l.substring(i + 5).toInt();
  if (age < 0) return l;
  l.remove(l.length() - 1);
  return l + ",\"age_ms\":" + String(age) + "}";
}

void replay() {  // send queued lines in order; stop at first failure and keep the rest
  File f = LittleFS.open(BACKLOG, "r");
  if (!f) return;
  String rest;
  bool ok = true;
  while (f.available()) {
    String l = f.readStringUntil('\n');
    if (!l.length()) continue;
    if (ok && post(withAge(l))) continue;
    ok = false; rest += l + "\n";
  }
  f.close();
  if (rest.length()) { f = LittleFS.open(BACKLOG, "w"); f.print(rest); f.close(); }
  else LittleFS.remove(BACKLOG);
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_TRIG, OUTPUT); pinMode(PIN_ECHO, INPUT); pinMode(PIN_FLAME, INPUT);
  pinMode(PIN_DS_VCC, OUTPUT); digitalWrite(PIN_DS_VCC, HIGH); delay(20);  // power the probe
  pinMode(PIN_RAIN_VCC, OUTPUT); digitalWrite(PIN_RAIN_VCC, HIGH);  // power the rain board
  ds.begin(); ds.requestTemperatures();  // throw away the 85.0 power-on value
  LittleFS.begin(true);
  bootId = esp_random();  // makes (node,boot,seq) unique across reboots
  LoRa.setPins(LORA_SS, LORA_RST, LORA_DIO0);
  loraOk = LoRa.begin(LORA_FREQ);
  Serial.println(loraOk ? "LoRa ready" : "LoRa init failed (check wiring/frequency)");
#ifndef NODE_LORA_ONLY  // test build: no Wi-Fi at all, so every reading goes out over LoRa
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  for (int i = 0; i < 20 && WiFi.status() != WL_CONNECTED; i++) delay(500);  // first reading shouldn't race the connect
#endif
}

void loop() {
  String j = readingJson();
  Serial.println(j);
  if (post(j)) replay();
  else {
    if (loraOk) { LoRa.beginPacket(); LoRa.print(j); LoRa.endPacket(); }
    queue(j);  // server dedups the later Wi-Fi replay of the same (boot,seq)
  }
  delay(INTERVAL_MS);
}
