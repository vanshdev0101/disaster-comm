#include <Arduino.h>
#include "config.h"
// live rain watch: raw ADC on PIN_RAIN, 5 per second
void setup() { Serial.begin(115200); pinMode(PIN_RAIN_VCC, OUTPUT); digitalWrite(PIN_RAIN_VCC, HIGH); }
void loop() { Serial.printf("raw=%d\n", analogRead(PIN_RAIN)); delay(200); }
