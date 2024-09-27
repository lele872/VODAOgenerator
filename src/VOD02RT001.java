import lombok.Data;
import lombok.EqualsAndHashCode;
import voPackage.Column;
import voPackage.Id;
import voPackage.Table;
import voPackage.VOGenerator;

import java.math.BigDecimal;

@EqualsAndHashCode(callSuper = true)
@Table
@Data
public class VOD02RT001 extends VOGenerator {

    @Column
    @Id
    private String D02001_2222;
}
