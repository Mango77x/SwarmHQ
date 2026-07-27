package com.swarmhq.service;

import com.swarmhq.repository.RiskZoneRepository;
import com.swarmhq.web.ZoneResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ZoneService {

    private final RiskZoneRepository riskZoneRepository;

    public ZoneService(RiskZoneRepository riskZoneRepository) {
        this.riskZoneRepository = riskZoneRepository;
    }

    public List<ZoneResponse> listAll() {
        return riskZoneRepository.findAll().stream()
                .map(ZoneResponse::from)
                .toList();
    }
}
