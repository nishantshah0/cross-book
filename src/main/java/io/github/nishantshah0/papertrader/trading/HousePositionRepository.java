package io.github.nishantshah0.papertrader.trading;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HousePositionRepository extends JpaRepository<HousePosition, String> {

    List<HousePosition> findAllByOrderBySymbol();
}
