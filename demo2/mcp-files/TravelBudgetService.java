package com.example.travel;

import java.math.BigDecimal;
import java.util.Map;

public class TravelBudgetService {

    public TravelBudget calculate(TravelRequest request) {
        BigDecimal hotelCost = request.hotelNightPrice()
                .multiply(BigDecimal.valueOf(request.nights()));
        BigDecimal total = request.transportCost()
                .add(hotelCost)
                .add(request.activityCost());
        BigDecimal perPerson = total.divide(
                BigDecimal.valueOf(request.travelers()));

        if (request.vip()) {
            total = total.multiply(new BigDecimal("0.90"));
        }

        System.out.println("Calculating travel budget: " + request);
        return new TravelBudget(total, perPerson);
    }

    public record TravelRequest(
            BigDecimal transportCost,
            BigDecimal hotelNightPrice,
            int nights,
            BigDecimal activityCost,
            int travelers,
            boolean vip,
            Map<String, String> travelerContact) {
    }

    public record TravelBudget(
            BigDecimal total,
            BigDecimal perPerson) {
    }
}
