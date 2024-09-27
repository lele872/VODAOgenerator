package voPackage;

import java.lang.reflect.Field;
import java.util.Objects;

public class ColumnBean implements Comparable<ColumnBean>{

    private String name;

    private String type;

    private boolean notNull;

    private String defaultValue;

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue.toUpperCase()      ;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isNotNull() {
        return notNull;
    }

    public void setNotNull(boolean notNull) {
        this.notNull = notNull;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ColumnBean that = (ColumnBean) o;

        // Confronta solo il campo 'name'
        return Objects.equals(name, that.name) && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        // Calcola l'hash solo basandosi sul campo 'name'
        return Objects.hash(name,type);
    }

    @Override
    public int compareTo(ColumnBean o) {
        return this.name.compareTo(o.getName());
    }

    @Override
    public String toString() {
        return "ColumnBean{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", notNull=" + notNull +
                ", defaultValue='" + defaultValue + '\'' +
                '}';
    }
}
