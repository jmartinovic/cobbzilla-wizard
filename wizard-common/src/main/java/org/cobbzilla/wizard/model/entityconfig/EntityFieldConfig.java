package org.cobbzilla.wizard.model.entityconfig;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static org.cobbzilla.util.daemon.ZillaRuntime.empty;
import static org.cobbzilla.util.json.JsonUtil.json;
import static org.cobbzilla.util.string.StringUtil.camelCaseToString;

/**
 * Defines how to work with a particular field defined in an EntityConfig
 */
@Slf4j @Accessors(chain=true)
public class EntityFieldConfig {

    public static EntityFieldConfig field(String name) { return new EntityFieldConfig().setName(name); } // convenience method

    /**
     * The name of the field. This field is optional when declaring configs via JSON, it will be populated with the key name
     * used in the EntityConfig's `fields` map.
     */
    @Getter @Setter private String name;

    @Setter private String displayName;
    /**
     * The display name of the field.
     * Default value: the value of the `name` field
     * @return The display name of the field
     */
    public String getDisplayName() { return !empty(displayName) ? displayName : camelCaseToString(name); }

    /**
     * The mode of the field. Allowed modes are 'standard', 'createOnly', 'readOnly'
     */
    @Getter @Setter private EntityFieldMode mode = EntityFieldMode.standard;

    /**
     * The data type of the field.
     */
    @Getter @Setter private EntityFieldType type = EntityFieldType.string;
    public boolean hasType() { return !empty(type); }

    /**
     * For data types like 'string', this is the length of the field.
     */
    @Getter @Setter private Integer length = null;
    public boolean hasLength () { return length != null; }

    @Setter private EntityFieldControl control;
    /**
     * The preferred kind of UI control to use when displaying the field.
     */
    public EntityFieldControl getControl() {
        if (control != null) return control;
        switch (type) {
            case flag:                  return EntityFieldControl.flag;
            case date_future: case date_past:
            case epoch_time: case date: return EntityFieldControl.date;
            case year: case year_future:
            case year_past: case age:   return EntityFieldControl.select;
            default:                    return hasLength() && length > 200 ? EntityFieldControl.textarea : EntityFieldControl.text;
        }
    }

    /**
     * When the value of the 'control' field is 'EntityFieldControl.select', this determines the source of the options
     * in the select list. It can be: <ul><li>
     * a comma-separated string of values
     * </li><li>JSON representing an array of EntityFieldOption objects
     * </li><li>a special string 'uri:api-path:value:displayValue', this means:<ul><li>
     *        </li><li> do a GET of api-path
     *        </li><li> expect the response to be a JSON array of objects
     *        </li><li> for each object in the array, use the 'value' field for the option value, and the 'displayValue' field for the option's display value
     *      </li></ul>
     * </li></ul>
     */
    @Getter @Setter private String options;

    /** the value of the special (usually first-listed) option that indicates no selection has been made */
    @Getter @Setter private String emptyDisplayValue;

    /**
     * Get the options as a list. This assumes that options is set of comma-separated values. URI-based options will return null.
     * @return An array of EntityFieldOptions, or null if there were no options or options were URI-based
     */
    public EntityFieldOption[] getOptionsList() {

        if (empty(options)) return null;

        if (options.startsWith("uri:")) {
            log.debug("getOptionsArray: cannot convert uri-style options to array: "+options);
            return null;
        }

        if (options.trim().startsWith("[")) {
            return json(options, EntityFieldOption[].class);
        } else {
            final List<EntityFieldOption> opts = new ArrayList<>();
            for (String opt : options.split(",")) opts.add(new EntityFieldOption(opt.trim()));
            return opts.toArray(new EntityFieldOption[opts.size()]);
        }
    }

    public void setOptionsList(EntityFieldOption[] options) { this.options = json(options); }

    /**
     * When the value of 'type' is 'reference', this provides details about how to find the referenced object.
     */
    @Getter @Setter private EntityFieldReference reference = null;
    @JsonIgnore public boolean isParentReference () {
        return getType() == EntityFieldType.reference && getReference().getEntity().equals(EntityFieldReference.REF_PARENT);
    }

    /**
     * When the value of 'type' is 'embedded', this is the name of the EntityConfig to use when working with the embedded object.
     */
    @Getter @Setter private String objectType;

}
