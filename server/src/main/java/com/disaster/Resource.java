package com.disaster;

import jakarta.persistence.*;

@Entity
public class Resource {
    @Id @GeneratedValue public Long id;
    public String name, status;
    public Long incident;  // incident this resource is deployed to, if any
}
