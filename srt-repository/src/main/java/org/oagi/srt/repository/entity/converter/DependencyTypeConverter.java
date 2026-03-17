package org.oagi.srt.repository.entity.converter;

import org.oagi.srt.repository.entity.ModuleDep;

import javax.persistence.AttributeConverter;

// (JAF), 20260317, Removed @Converter(autoApply=true): workaround for Hibernate 5.0.x duplicate converter registration bug
public class DependencyTypeConverter implements AttributeConverter<ModuleDep.DependencyType, Integer> {
    @Override
    public Integer convertToDatabaseColumn(ModuleDep.DependencyType attribute) {
        return (attribute == null) ? -1 : attribute.getValue();
    }

    @Override
    public ModuleDep.DependencyType convertToEntityAttribute(Integer dbData) {
        if (dbData == null || dbData < 0) {
            return null;
        }
        return ModuleDep.DependencyType.valueOf(dbData);
    }
}
