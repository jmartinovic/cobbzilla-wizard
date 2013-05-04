package org.cobbzilla.wizard.model;

public interface Identifiable {

<<<<<<< HEAD
import javax.persistence.*;
import javax.validation.constraints.Size;
import java.util.UUID;

import static org.cobbzilla.wizard.model.BasicConstraintConstants.*;

@MappedSuperclass @EqualsAndHashCode(of={"id"})
public class Identifiable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    @Column(unique=true, nullable=false, updatable=false)
    @Getter @Setter protected Long id;

    @Column(unique=true, updatable=false, nullable=false, length= UUID_MAXLEN)
    @Size(max=UUID_MAXLEN, message=ERR_UUID_LENGTH)
    @Getter @Setter private volatile String uuid = null;

    public void beforeCreate() {
        if (uuid != null) throw new IllegalStateException("uuid already initialized");
        uuid = UUID.randomUUID().toString();
    }

    public void update(Identifiable thing) {
        Long existingId = getId();
        String existingUuid = getUuid();
        try {
            BeanUtils.copyProperties(this, thing);

        } catch (Exception e) {
            throw new IllegalArgumentException("update: error copying properties: "+e, e);

        } finally {
            // Do not allow these to be updated
            setId(existingId);
            setUuid(existingUuid);
        }
    }

    @Column(updatable=false, nullable=false)
    @Getter @Setter protected long ctime = System.currentTimeMillis();
=======
    public Long getId();
    public void setId(Long id);

    public String getUuid();
    public void setUuid(String uuid);

    public void beforeCreate();

>>>>>>> auth2
}
