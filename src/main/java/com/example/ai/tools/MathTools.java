package com.example.ai.tools;

import org.springframework.ai.tool.annotation.Tool;

public class MathTools {

    @Tool(description = "Add two numbers and return the result")
    public double add(double a, double b) {
        return a + b;
    }

    @Tool(description = "Multiply two numbers and return the result")
    public double multiply(double a, double b) {
        return a * b;
    }

    @Tool(description = "Calculate percentage: what is 'percent'% of 'value'")
    public double percentage(double value, double percent) {
        return value * percent / 100.0;
    }
}
