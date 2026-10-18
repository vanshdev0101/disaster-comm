package com.disaster;

import jakarta.persistence.*;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"node", "boot", "seq"}),
       indexes = @Index(name = "reading_node_recv", columnList = "node, recv"))
public class Reading {  // public fields: plain JPA field access, no getter boilerplate
    @Id @GeneratedValue public Long id;
    public String node;
    public long boot;
    public long seq;
    public double recv;  // epoch seconds, same unit the dashboard expects
    public String via;
    public Integer rssi, levelCm, rain, smoke, flame;
    public Double tempC;
}
