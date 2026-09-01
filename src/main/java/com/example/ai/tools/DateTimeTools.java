package com.example.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateTimeTools {

    @Tool(description = "Get the current date and time in ISO format (e.g. 2026-09-01T15:00:00)")
    public String getCurrentDateTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Tool(description = "Get the current date in ISO format (e.g. 2026-09-01)")
    public String getCurrentDate() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Tool(description = "Get the current year as a number")
    public String getCurrentYear() {
        return String.valueOf(LocalDate.now().getYear());
    }
}
