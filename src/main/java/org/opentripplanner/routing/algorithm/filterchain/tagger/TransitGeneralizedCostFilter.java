package org.opentripplanner.routing.algorithm.filterchain.tagger;

import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.routing.algorithm.filterchain.ItineraryListFilter;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.DoubleFunction;
import java.util.stream.Collectors;

/**
 * This filter remove all transit results witch have a generalized-cost higher than
 * the max-limit computed by the {@link #costLimitFunction}.
 * <p>
 * @see org.opentripplanner.routing.api.request.ItineraryFilterParameters#transitGeneralizedCostLimit
 */
public class TransitGeneralizedCostFilter implements ItineraryTagger {
  private final DoubleFunction<Double> costLimitFunction;

  public TransitGeneralizedCostFilter(DoubleFunction<Double> costLimitFunction) {
    this.costLimitFunction = costLimitFunction;
  }

  @Override
  public String name() {
    return "transit-cost-filter";
  }

  @Override
  public void tagItineraries(List<Itinerary> itineraries) {
    OptionalDouble minGeneralizedCost = itineraries
        .stream()
        .filter(Itinerary::hasTransit)
        .mapToDouble(it -> it.generalizedCost)
        .min();

    if(minGeneralizedCost.isEmpty()) { return; }

    final double maxLimit = costLimitFunction.apply(minGeneralizedCost.getAsDouble());

    itineraries.stream().filter(
            it -> it.hasTransit() && it.generalizedCost > maxLimit
    ).forEach(it -> it.markAsDeleted(notice()));  }
}
