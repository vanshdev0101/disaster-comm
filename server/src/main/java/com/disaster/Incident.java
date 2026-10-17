package com.disaster;

import jakarta.persistence.*;

@Entity
public class Incident {
    @Id @GeneratedValue public Long id;
    public String node, type;
    public double opened;  // epoch seconds
    public String status;  // open -> acknowledged -> resolved
}
