import lombok.Data;
import lombok.EqualsAndHashCode;
import voPackage.Column;
import voPackage.Id;
import voPackage.Entity;
import voPackage.VOGenerator;

@EqualsAndHashCode(callSuper = true)
@Entity
@Data
public class VOD02RT001 extends VOGenerator {

    @Column
    @Id
    private String D02001_2222;
}
