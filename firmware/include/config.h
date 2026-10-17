#pragma once
#if __has_include("secrets.h")
#include "secrets.h"
#else
#define WIFI_SSID "changeme"
#define WIFI_PASS "changeme"
#define SERVER_URL "http://192.168.1.50:8000/api/readings"
#endif

#define NODE_ID "node1"
#define INTERVAL_MS 10000
#define LORA_FREQ 433E6  // Ra-02 (SX1278) is a 410-525 MHz part. Both radios must match; use 866E6 only with an 868 MHz RFM95W

// LoRa (RFM95/Ra-02) on VSPI
#define LORA_SS 5
#define LORA_RST 14
#define LORA_DIO0 26

// sensors (ADC1 pins only: ADC2 dies when Wi-Fi is on)
#define PIN_DS18B20 4
#define PIN_DS_VCC 15  // DS18B20 is powered from this pin (set HIGH): the 3V3 pin has no free hole left
#define PIN_TRIG 25
#define PIN_ECHO 33   // JSN-SR04T echo is 5 V: use a divider
#define PIN_FLAME 27  // KY-026 digital out, LOW = flame
#define PIN_SMOKE 34  // MQ-2 analog, divider needed from 5 V
#define HAVE_SMOKE 0  // 1 once an MQ-2 is wired; 0 reports -1 ("no sensor") instead of a floating pin
#define PIN_RAIN 32  // ADC1 pin, safe with Wi-Fi
#define PIN_RAIN_VCC 13  // rain board powered from this pin (set HIGH): keeps D15 from carrying too much
#define RAIN_WET_RAW 1000  // raw ADC when the plate is soaked; dry reads ~4095 (calibrate on site)
#define MOUNT_CM 200  // sensor height above the dry bed; calibrate on site
