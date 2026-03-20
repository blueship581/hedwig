package com.blueship581.hedwig.service;

import com.blueship581.hedwig.domain.entity.GlucoseReading;
import com.blueship581.hedwig.domain.entity.MonitoredSubject;
import com.blueship581.hedwig.domain.entity.VendorConnection;
import com.blueship581.hedwig.domain.repository.GlucoseReadingRepository;
import com.blueship581.hedwig.domain.repository.MonitoredSubjectRepository;
import com.blueship581.hedwig.domain.repository.VendorConnectionRepository;
import com.blueship581.hedwig.dto.GlucoseReadingDto;
import com.blueship581.hedwig.dto.SyncResultDto;
import com.blueship581.hedwig.exception.ResourceNotFoundException;
import com.blueship581.hedwig.util.GlucoseConverter;
import com.blueship581.hedwig.vendor.client.VendorClient;
import com.blueship581.hedwig.vendor.client.VendorClientFactory;
import com.blueship581.hedwig.vendor.model.VendorGlucoseData;
import com.blueship581.hedwig.vendor.model.VendorSubject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlucoseService {

    private final GlucoseReadingRepository readingRepository;
    private final MonitoredSubjectRepository subjectRepository;
    private final VendorConnectionRepository connectionRepository;
    private final VendorClientFactory vendorClientFactory;
    private final GlucoseConverter glucoseConverter;

    @Transactional
    public GlucoseReadingDto fetchLatest(Long connectionId, Long subjectId) {
        VendorConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> ResourceNotFoundException.vendorConnection(connectionId));
        MonitoredSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> ResourceNotFoundException.monitoredSubject(subjectId));

        VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
        VendorSubject vendorSubject = toVendorSubject(subject);

        VendorGlucoseData latest = client.getLatestGlucose(
                connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

        saveIfNew(subject.getId(), latest);
        GlucoseReading reading = readingRepository
                .findTopByMonitoredSubjectIdOrderByReadingTimeDesc(subject.getId())
                .orElseThrow(() -> ResourceNotFoundException.syncedGlucoseReadings(subjectId));
        return toDto(reading);
    }

    @Transactional
    public List<GlucoseReadingDto> fetchHistory(Long connectionId, Long subjectId) {
        VendorConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> ResourceNotFoundException.vendorConnection(connectionId));
        MonitoredSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> ResourceNotFoundException.monitoredSubject(subjectId));

        VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
        VendorSubject vendorSubject = toVendorSubject(subject);

        List<VendorGlucoseData> history = client.getHistoricalGlucose(
                connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

        int saved = 0;
        for (VendorGlucoseData data : history) {
            saveIfNew(subject.getId(), data);
            saved++;
        }
        log.debug("Fetched {} historical readings for subject {}, deduped into DB", saved, subjectId);

        return getReadings(subjectId);
    }

    public List<GlucoseReadingDto> getReadings(Long subjectId) {
        List<GlucoseReading> readings = readingRepository
                .findByMonitoredSubjectIdOrderByReadingTimeDesc(subjectId);

        if (readings.isEmpty()) {
            readings = fetchHistoryEntitiesForSubject(subjectId);
        }

        return readings
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public GlucoseReadingDto getLatestReading(Long subjectId) {
        return readingRepository.findTopByMonitoredSubjectIdOrderByReadingTimeDesc(subjectId)
                .or(() -> {
                    fetchHistoryEntitiesForSubject(subjectId);
                    return readingRepository.findTopByMonitoredSubjectIdOrderByReadingTimeDesc(subjectId);
                })
                .map(this::toDto)
                .orElseThrow(() -> ResourceNotFoundException.glucoseReadings(subjectId));
    }

    /**
     * Save a glucose reading obtained externally (e.g. from the Ottai relatives endpoint)
     * if it doesn't already exist. Returns true when new data was persisted.
     */
    @Transactional
    public boolean saveIfNewReading(Long subjectId, VendorGlucoseData data) {
        return saveIfNew(subjectId, data);
    }

    @Transactional
    public SyncResultDto syncHistory(Long subjectId) {
        MonitoredSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> ResourceNotFoundException.monitoredSubject(subjectId));
        VendorConnection connection = connectionRepository.findById(subject.getVendorConnectionId())
                .orElseThrow(() -> ResourceNotFoundException.vendorConnection(subject.getVendorConnectionId()));

        VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
        VendorSubject vendorSubject = toVendorSubject(subject);

        List<VendorGlucoseData> history = client.getHistoricalGlucose(
                connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

        if (history.isEmpty()) {
            return SyncResultDto.builder().syncedCount(0).build();
        }

        Instant minTime = history.stream()
                .map(VendorGlucoseData::getReadingTime)
                .min(Instant::compareTo).orElseThrow();
        Instant maxTime = history.stream()
                .map(VendorGlucoseData::getReadingTime)
                .max(Instant::compareTo).orElseThrow();

        readingRepository.deleteByMonitoredSubjectIdAndReadingTimeBetween(
                subjectId, minTime, maxTime);

        for (VendorGlucoseData data : history) {
            saveIfNew(subjectId, data);
        }

        connection.setLastSyncedAt(Instant.now());
        connectionRepository.save(connection);

        log.info("Synced {} readings for subject {} (range {} ~ {})",
                history.size(), subjectId, minTime, maxTime);

        return SyncResultDto.builder()
                .syncedCount(history.size())
                .timeRangeStart(minTime)
                .timeRangeEnd(maxTime)
                .build();
    }

    /**
     * Poll the latest glucose from vendor and return whether new data was saved.
     * Used by the scheduler to decide polling frequency.
     */
    @Transactional
    public boolean pollLatest(Long connectionId, Long subjectId) {
        VendorConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> ResourceNotFoundException.vendorConnection(connectionId));
        MonitoredSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> ResourceNotFoundException.monitoredSubject(subjectId));

        VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
        VendorSubject vendorSubject = toVendorSubject(subject);

        VendorGlucoseData latest = client.getLatestGlucose(
                connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

        boolean isNew = saveIfNew(subject.getId(), latest);
        if (isNew) {
            connection.setLastSyncedAt(Instant.now());
            connectionRepository.save(connection);
        }
        return isNew;
    }

    private boolean saveIfNew(Long subjectId, VendorGlucoseData data) {
        if (readingRepository.existsByMonitoredSubjectIdAndReadingTime(subjectId, data.getReadingTime())) {
            return false;
        }

        double mgdl = glucoseConverter.mmolToMgdl(data.getGlucoseMmol());
        GlucoseReading reading = GlucoseReading.builder()
                .monitoredSubjectId(subjectId)
                .glucoseMmol(data.getGlucoseMmol())
                .glucoseMgdl(mgdl)
                .trendDirection(data.getTrendDirection())
                .readingTime(data.getReadingTime())
                .pushedToNightscout(false)
                .build();
        readingRepository.save(reading);
        return true;
    }

    private List<GlucoseReading> fetchHistoryEntitiesForSubject(Long subjectId) {
        MonitoredSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> ResourceNotFoundException.monitoredSubject(subjectId));
        VendorConnection connection = connectionRepository.findById(subject.getVendorConnectionId())
                .orElseThrow(() -> ResourceNotFoundException.vendorConnection(subject.getVendorConnectionId()));

        VendorClient client = vendorClientFactory.getClient(connection.getVendorType());
        VendorSubject vendorSubject = toVendorSubject(subject);
        List<VendorGlucoseData> history = client.getHistoricalGlucose(
                connection.getAccessToken(), connection.getVendorUserId(), vendorSubject);

        for (VendorGlucoseData data : history) {
            saveIfNew(subjectId, data);
        }

        connection.setLastSyncedAt(Instant.now());
        connectionRepository.save(connection);

        return readingRepository.findByMonitoredSubjectIdOrderByReadingTimeDesc(subjectId);
    }

    private VendorSubject toVendorSubject(MonitoredSubject s) {
        return VendorSubject.builder()
                .subjectId(s.getVendorSubjectId())
                .deviceId(s.getVendorDeviceId())
                .displayName(s.getDisplayName())
                .build();
    }

    private GlucoseReadingDto toDto(GlucoseReading r) {
        return GlucoseReadingDto.builder()
                .id(r.getId())
                .monitoredSubjectId(r.getMonitoredSubjectId())
                .glucoseMmol(r.getGlucoseMmol())
                .glucoseMgdl(r.getGlucoseMgdl())
                .trendDirection(r.getTrendDirection())
                .readingTime(r.getReadingTime())
                .pushedToNightscout(r.getPushedToNightscout())
                .build();
    }
}
