package com.spring.replaceMd;


import java.text.SimpleDateFormat;
import java.util.Date;

public class MvcService {
    public String getTime() {
        SimpleDateFormat formate = new SimpleDateFormat("yy-MM-dd");
        return formate.format(new Date());
    }
}