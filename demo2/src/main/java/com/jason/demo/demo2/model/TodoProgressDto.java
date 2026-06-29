package com.jason.demo.demo2.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodoProgressDto {

    private int completed;
    private int total;
    private int percent;
}
