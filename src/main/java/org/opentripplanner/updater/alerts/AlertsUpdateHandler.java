/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.updater.alerts;

import java.util.*;

import org.opentripplanner.model.AgencyAndId;
import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.*;
import org.opentripplanner.model.AgencyAndId;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.calendar.ServiceDate;
import org.opentripplanner.routing.alertpatch.Alert;
import org.opentripplanner.routing.alertpatch.AlertPatch;
import org.opentripplanner.routing.alertpatch.TimePeriod;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.services.AlertPatchService;
import org.opentripplanner.updater.GtfsRealtimeFuzzyTripMatcher;
import org.opentripplanner.updater.SiriFuzzyTripMatcher;
import org.opentripplanner.util.I18NString;
import org.opentripplanner.util.TranslatedString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.ifopt.siri20.StopPlaceRef;
import uk.org.siri.siri20.*;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * This updater only includes GTFS-Realtime Service Alert feeds.
 * @author novalis
 *
 */
public class AlertsUpdateHandler {
    private static final Logger log = LoggerFactory.getLogger(AlertsUpdateHandler.class);

    private String feedId;

    private Set<String> patchIds = new HashSet<String>();

    private AlertPatchService alertPatchService;

    /** How long before the posted start of an event it should be displayed to users */
    private long earlyStart;

    /** Set only if we should attempt to match the trip_id from other data in TripDescriptor */
    private GtfsRealtimeFuzzyTripMatcher fuzzyTripMatcher;


    private SiriFuzzyTripMatcher siriFuzzyTripMatcher;

    public void update(FeedMessage message) {
        alertPatchService.expire(patchIds);
        patchIds.clear();

        for (FeedEntity entity : message.getEntityList()) {
            if (!entity.hasAlert()) {
                continue;
            }
            GtfsRealtime.Alert alert = entity.getAlert();
            String id = entity.getId();
            handleAlert(id, alert);
        }
    }

    public void update(ServiceDelivery delivery) {
        for (SituationExchangeDeliveryStructure sxDelivery : delivery.getSituationExchangeDeliveries()) {
            SituationExchangeDeliveryStructure.Situations situations = sxDelivery.getSituations();
            if (situations != null) {
                AtomicInteger alertCounter = new AtomicInteger(0);
                for (PtSituationElement sxElement : situations.getPtSituationElements()) {
                    handleAlert(sxElement, alertCounter);
                }
                log.info("Added {} alerts based on {} situations, current alert-count: {}", alertCounter.intValue(), situations.getPtSituationElements().size(), alertPatchService.getAllAlertPatches().size());
            }
        }
    }

    private void handleAlert(PtSituationElement situation, AtomicInteger alertCounter) {
        Alert alert = new Alert();

        if (situation.getDescriptions() != null && !situation.getDescriptions().isEmpty()) {
            alert.alertDescriptionText = getTranslatedString(situation.getDescriptions());
        } else {
            alert.alertDescriptionText = getTranslatedString(situation.getDetails());
        }
        alert.alertHeaderText = getTranslatedString(situation.getSummaries());

        ArrayList<TimePeriod> periods = new ArrayList<>();
        if(situation.getValidityPeriods().size() > 0) {
            long bestStartTime = Long.MAX_VALUE;
            long bestEndTime = 0;
            for (HalfOpenTimestampOutputRangeStructure activePeriod : situation.getValidityPeriods()) {

                final long realStart = activePeriod.getStartTime() != null ? activePeriod.getStartTime().toInstant().toEpochMilli() : 0;
                final long start = activePeriod.getStartTime() != null? realStart - earlyStart : 0;
                if (realStart > 0 && realStart < bestStartTime) {
                    bestStartTime = realStart;
                }

                final long realEnd = activePeriod.getEndTime() != null ? activePeriod.getEndTime().toInstant().toEpochMilli() : 0;
                final long end = activePeriod.getEndTime() != null? realEnd  : 0;
                if (realEnd > 0 && realEnd > bestEndTime) {
                    bestEndTime = realEnd;
                }

                periods.add(new TimePeriod(start, end));
            }
            if (bestStartTime != Long.MAX_VALUE) {
                alert.effectiveStartDate = new Date(bestStartTime);
            }
            if (bestEndTime != 0) {
                alert.effectiveEndDate = new Date(bestEndTime);
            }
        } else {
            // Per the GTFS-rt spec, if an alert has no TimeRanges, than it should always be shown.
            periods.add(new TimePeriod(0, Long.MAX_VALUE));
        }

        String situationNumber = null;

        if (situation.getSituationNumber() != null) {
            situationNumber = situation.getSituationNumber().getValue();
        }

        String paddedSituationNumber = situationNumber + ":";

        Set<String> idsToExpire = new HashSet<>();
        boolean expireSituation = (situation.getProgress() != null &&
                situation.getProgress().equals(WorkflowStatusEnumeration.CLOSED));

        Set<AlertPatch> patches = new HashSet<>();
        AffectsScopeStructure affectsStructure = situation.getAffects();

        if (affectsStructure != null) {

            AffectsScopeStructure.Operators operators = affectsStructure.getOperators();

            if (operators != null && !isListNullOrEmpty(operators.getAffectedOperators())) {
                for (AffectedOperatorStructure affectedOperator : operators.getAffectedOperators()) {

                    OperatorRefStructure operatorRef = affectedOperator.getOperatorRef();
                    if (operatorRef == null || operatorRef.getValue() == null) {
                        continue;
                    }

                    String operator = operatorRef.getValue();

                    String id = paddedSituationNumber + operator;
                    if (expireSituation) {
                        idsToExpire.add(id);
                    } else {
                        AlertPatch alertPatch = new AlertPatch();
                        alertPatch.setAgencyId(operator);
                        alertPatch.setTimePeriods(periods);
                        alertPatch.setAlert(alert);
                        alertPatch.setId(id);
                        patches.add(alertPatch);
                    }
                }
            }

            AffectsScopeStructure.StopPoints stopPoints = affectsStructure.getStopPoints();

            if (stopPoints != null && !isListNullOrEmpty(stopPoints.getAffectedStopPoints())) {

                for (AffectedStopPointStructure stopPoint : stopPoints.getAffectedStopPoints()) {
                    StopPointRef stopPointRef = stopPoint.getStopPointRef();
                    if (stopPointRef == null || stopPointRef.getValue() == null) {
                        continue;
                    }

                    AgencyAndId stopId = siriFuzzyTripMatcher.getStop(stopPointRef.getValue());

                    String id = paddedSituationNumber + stopPointRef.getValue();
                    if (stopId != null) {
                        AlertPatch alertPatch = new AlertPatch();
                        alertPatch.setStop(stopId);
                        alertPatch.setTimePeriods(periods);
                        alertPatch.setId(id);
                        patches.add(alertPatch);
                    }
                }
            }

            AffectsScopeStructure.StopPlaces stopPlaces = affectsStructure.getStopPlaces();

            if (stopPlaces != null && !isListNullOrEmpty(stopPlaces.getAffectedStopPlaces())) {

                for (AffectedStopPlaceStructure stopPoint : stopPlaces.getAffectedStopPlaces()) {
                    StopPlaceRef stopPlace = stopPoint.getStopPlaceRef();
                    if (stopPlace == null || stopPlace.getValue() == null) {
                        continue;
                    }

                    AgencyAndId stopId = siriFuzzyTripMatcher.getStop(stopPlace.getValue());

                    String id = paddedSituationNumber + stopPlace.getValue();
                    if (stopId != null) {

                        AlertPatch alertPatch = new AlertPatch();
                        alertPatch.setStop(stopId);
                        alertPatch.setTimePeriods(periods);
                        alertPatch.setId(id);
                        patches.add(alertPatch);
                    }
                }
            }

            AffectsScopeStructure.Networks networks = null;

            if (stopPoints == null && stopPlaces == null) {
                //NRP-2242: When Alert affects both Line and Stop, only Stop should be used
                networks = affectsStructure.getNetworks();
            }

            if (networks != null && !isListNullOrEmpty(networks.getAffectedNetworks())) {

                for (AffectsScopeStructure.Networks.AffectedNetwork affectedNetwork : networks.getAffectedNetworks()) {
                    List<AffectedLineStructure> affectedLines = affectedNetwork.getAffectedLines();
                    if (affectedLines != null && !isListNullOrEmpty(affectedLines)) {
                        for (AffectedLineStructure line : affectedLines) {

                            LineRef lineRef = line.getLineRef();

                            if (lineRef == null || lineRef.getValue() == null) {
                                continue;
                            }

                            Set<Route> affectedRoutes = siriFuzzyTripMatcher.getRoutes(lineRef.getValue());
                            for (Route route : affectedRoutes) {

                                String id = paddedSituationNumber + route.getId();
                                if (expireSituation) {
                                    idsToExpire.add(id);
                                } else {
                                    AlertPatch alertPatch = new AlertPatch();
                                    alertPatch.setRoute(route.getId());
                                    alertPatch.setTimePeriods(periods);
                                    alertPatch.setAgencyId(route.getAgency().getId());
                                    alertPatch.setId(id);
                                    patches.add(alertPatch);
                                }
                            }
                        }
                    }
                    NetworkRefStructure networkRef = affectedNetwork.getNetworkRef();
                    if (networkRef == null || networkRef.getValue() == null) {
                        continue;
                    }
                    String networkId = networkRef.getValue();

                    String id = paddedSituationNumber + networkId;
                    if (expireSituation) {
                        idsToExpire.add(id);
                    } else {
                        AlertPatch alertPatch = new AlertPatch();
                        alertPatch.setId(id);
                        alertPatch.setTimePeriods(periods);
                        patches.add(alertPatch);
                    }
                }
            }

            AffectsScopeStructure.VehicleJourneys vjs = affectsStructure.getVehicleJourneys();
            if (vjs != null && !isListNullOrEmpty(vjs.getAffectedVehicleJourneies())) {

                for (AffectedVehicleJourneyStructure affectedVehicleJourney : vjs.getAffectedVehicleJourneies()) {

                    String lineRef = null;
                    if (affectedVehicleJourney.getLineRef() != null) {
                        lineRef = affectedVehicleJourney.getLineRef().getValue();
                    }

                    List<VehicleJourneyRef> vehicleJourneyReves = affectedVehicleJourney.getVehicleJourneyReves();

                    ZonedDateTime originAimedDepartureTime = (affectedVehicleJourney.getOriginAimedDepartureTime() != null ? affectedVehicleJourney.getOriginAimedDepartureTime():ZonedDateTime.now());

                    ServiceDate serviceDate = new ServiceDate(originAimedDepartureTime.getYear(), originAimedDepartureTime.getMonthValue(), originAimedDepartureTime.getDayOfMonth());

                    ServiceDate yesterday = new ServiceDate().previous();

                    if (!isListNullOrEmpty(vehicleJourneyReves)) {
                        if (serviceDate.compareTo(yesterday) >= 0 ) {
                            for (VehicleJourneyRef vehicleJourneyRef : vehicleJourneyReves) {

                                AgencyAndId tripId = siriFuzzyTripMatcher.getTripId(vehicleJourneyRef.getValue());
                                if (tripId == null) {
                                    tripId = siriFuzzyTripMatcher.getTripIdForTripShortNameServiceDateAndMode(vehicleJourneyRef.getValue(),
                                            serviceDate,
                                            TraverseMode.RAIL);
                                }
                                if (tripId != null) {
                                    String id = paddedSituationNumber + tripId.getId();

                                    AlertPatch alertPatch = new AlertPatch();
                                    alertPatch.setTrip(tripId);
                                    alertPatch.setAgencyId(tripId.getAgencyId());
                                    alertPatch.setId(id);

                                    //  A tripId for a given date may be reused for other dates not affected by this alert.
                                    List<TimePeriod> timePeriodList = new ArrayList<>();
                                    timePeriodList.add(new TimePeriod(originAimedDepartureTime.toEpochSecond()*1000, originAimedDepartureTime.plusDays(1).toEpochSecond()*1000));
                                    alertPatch.setTimePeriods(timePeriodList);


                                    Alert vehicleJourneyAlert = new Alert();
                                    vehicleJourneyAlert.alertHeaderText = alert.alertHeaderText;
                                    vehicleJourneyAlert.alertDescriptionText = alert.alertDescriptionText;
                                    vehicleJourneyAlert.alertUrl = alert.alertUrl;
                                    vehicleJourneyAlert.effectiveStartDate = serviceDate.getAsDate();
                                    vehicleJourneyAlert.effectiveEndDate = serviceDate.next().getAsDate();

                                    alertPatch.setAlert(vehicleJourneyAlert);

                                    patches.add(alertPatch);
                                }
                            }
                        }
                    }
                    if (lineRef != null) {

                        Set<Route> affectedRoutes = siriFuzzyTripMatcher.getRoutes(lineRef);
                        for (Route route : affectedRoutes) {
                            String id = paddedSituationNumber + route.getId();
                            if (expireSituation) {
                                idsToExpire.add(id);
                            } else {
                                AlertPatch alertPatch = new AlertPatch();
                                alertPatch.setRoute(route.getId());
                                alertPatch.setAgencyId(route.getAgency().getId());
                                alertPatch.setTimePeriods(periods);
                                alertPatch.setId(id);
                                patches.add(alertPatch);
                            }
                        }
                    }
                }
            }
        }

        // Alerts are not partially updated - cancel ALL current related alerts before adding updated.
        idsToExpire.addAll(alertPatchService.getAllAlertPatches()
            .stream()
            .filter(alertPatch -> alertPatch.getId().startsWith(paddedSituationNumber))
            .map(alertPatch -> alertPatch.getId())
            .collect(Collectors.toList()));

        if (!patches.isEmpty() | !idsToExpire.isEmpty()) {
            alertPatchService.expire(idsToExpire);

            for (AlertPatch patch : patches) {
                if (patch.getAlert() == null) {
                    patch.setAlert(alert);
                }
                patchIds.add(patch.getId());
                alertPatchService.apply(patch);
                alertCounter.incrementAndGet();
            }
        } else {
            log.info("No match found for Alert - ignoring situation with situationNumber {}", situationNumber);
        }

    }

    private boolean isListNullOrEmpty(List list) {
        if (list == null || list.isEmpty()) {
            return true;
        }
        return false;
    }


    private void handleAlert(String id, GtfsRealtime.Alert alert) {
        Alert alertText = new Alert();
        alertText.alertDescriptionText = deBuffer(alert.getDescriptionText());
        alertText.alertHeaderText = deBuffer(alert.getHeaderText());
        alertText.alertUrl = deBuffer(alert.getUrl());
        ArrayList<TimePeriod> periods = new ArrayList<TimePeriod>();
        if(alert.getActivePeriodCount() > 0) {
            long bestStartTime = Long.MAX_VALUE;
            long lastEndTime = Long.MIN_VALUE;
            for (TimeRange activePeriod : alert.getActivePeriodList()) {
                final long realStart = activePeriod.hasStart() ? activePeriod.getStart() : 0;
                final long start = activePeriod.hasStart() ? realStart - earlyStart : 0;
                if (realStart > 0 && realStart < bestStartTime) {
                    bestStartTime = realStart;
                }
                final long end = activePeriod.hasEnd() ? activePeriod.getEnd() : Long.MAX_VALUE;
                if (end > lastEndTime) {
                    lastEndTime = end;
                }
                periods.add(new TimePeriod(start, end));
            }
            if (bestStartTime != Long.MAX_VALUE) {
                alertText.effectiveStartDate = new Date(bestStartTime * 1000);
            }
            if (lastEndTime != Long.MIN_VALUE) {
                alertText.effectiveEndDate = new Date(lastEndTime * 1000);
            }
        } else {
            // Per the GTFS-rt spec, if an alert has no TimeRanges, than it should always be shown.
            periods.add(new TimePeriod(0, Long.MAX_VALUE));
        }
        for (EntitySelector informed : alert.getInformedEntityList()) {
            if (fuzzyTripMatcher != null && informed.hasTrip()) {
                TripDescriptor trip = fuzzyTripMatcher.match(feedId, informed.getTrip());
                informed = informed.toBuilder().setTrip(trip).build();
            }
            String patchId = createId(id, informed);

            String routeId = null;
            if (informed.hasRouteId()) {
                routeId = informed.getRouteId();
            }

            int direction;
            if (informed.hasTrip() && informed.getTrip().hasDirectionId()) {
                direction = informed.getTrip().getDirectionId();
            } else {
                direction = -1;
            }

            // TODO: The other elements of a TripDescriptor are ignored...
            String tripId = null;
            if (informed.hasTrip() && informed.getTrip().hasTripId()) {
                tripId = informed.getTrip().getTripId();
            }
            String stopId = null;
            if (informed.hasStopId()) {
                stopId = informed.getStopId();
            }

            String agencyId = informed.getAgencyId();
            if (informed.hasAgencyId()) {
                agencyId = informed.getAgencyId().intern();
            }

            AlertPatch patch = new AlertPatch();
            patch.setFeedId(feedId);
            if (routeId != null) {
                patch.setRoute(new AgencyAndId(feedId, routeId));
                // Makes no sense to set direction if we don't have a route
                if (direction != -1) {
                    patch.setDirectionId(direction);
                }
            }
            if (tripId != null) {
                patch.setTrip(new AgencyAndId(feedId, tripId));
            }
            if (stopId != null) {
                patch.setStop(new AgencyAndId(feedId, stopId));
            }
            if (agencyId != null && routeId == null && tripId == null && stopId == null) {
                patch.setAgencyId(agencyId);
            }
            patch.setTimePeriods(periods);
            patch.setAlert(alertText);

            patch.setId(patchId);
            patchIds.add(patchId);

            alertPatchService.apply(patch);
        }
    }

    private String createId(String id, EntitySelector informed) {
        return id + " "
            + (informed.hasAgencyId  () ? informed.getAgencyId  () : " null ") + " "
            + (informed.hasRouteId   () ? informed.getRouteId   () : " null ") + " "
            + (informed.hasTrip() && informed.getTrip().hasDirectionId() ?
                informed.getTrip().hasDirectionId() : " null ") + " "
            + (informed.hasRouteType () ? informed.getRouteType () : " null ") + " "
            + (informed.hasStopId    () ? informed.getStopId    () : " null ") + " "
            + (informed.hasTrip() && informed.getTrip().hasTripId() ?
                informed.getTrip().getTripId() : " null ");
    }

    /**
     * convert a protobuf TranslatedString to a OTP TranslatedString
     *
     * @return A TranslatedString containing the same information as the input
     */
    private I18NString deBuffer(GtfsRealtime.TranslatedString input) {
        Map<String, String> translations = new HashMap<>();
        for (GtfsRealtime.TranslatedString.Translation translation : input.getTranslationList()) {
            String language = translation.getLanguage();
            String string = translation.getText();
            translations.put(language, string);
        }
        return translations.isEmpty() ? null : TranslatedString.getI18NString(translations);
    }

    /**
     * convert a SIRI DefaultedTextStructure to a OTP TranslatedString
     *
     * @return A TranslatedString containing the same information as the input
     * @param input
     */
    private I18NString getTranslatedString(List<DefaultedTextStructure> input) {
        Map<String, String> translations = new HashMap<>();
        if (input != null && input.size() > 0) {
            for (DefaultedTextStructure textStructure : input) {
                String language = "";
                String value = "";
                if (textStructure.getLang() != null) {
                    language = textStructure.getLang();
                }
                if (textStructure.getValue() != null) {
                    value = textStructure.getValue();
                }
                translations.put(language, value);
            }
        } else {
            translations.put("", "");
        }

        return translations.isEmpty() ? null : TranslatedString.getI18NString(translations);
    }

    public void setFeedId(String feedId) {
        if(feedId != null)
            this.feedId = feedId.intern();
    }

    public void setAlertPatchService(AlertPatchService alertPatchService) {
        this.alertPatchService = alertPatchService;
    }

    public long getEarlyStart() {
        return earlyStart;
    }

    public void setEarlyStart(long earlyStart) {
        this.earlyStart = earlyStart;
    }

    public void setFuzzyTripMatcher(GtfsRealtimeFuzzyTripMatcher fuzzyTripMatcher) {
        this.fuzzyTripMatcher = fuzzyTripMatcher;
    }

    public void setSiriFuzzyTripMatcher(SiriFuzzyTripMatcher siriFuzzyTripMatcher) {
        this.siriFuzzyTripMatcher = siriFuzzyTripMatcher;
    }
}
