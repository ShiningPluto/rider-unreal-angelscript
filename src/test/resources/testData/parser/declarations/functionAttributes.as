class UAttributeExample
{
    bool TryGetValue(int&out OutValue) const no_discard
    {
        return false;
    }

    void OldMethod() deprecated {}
}

bool TryDraw(float LineWeight = 1.0) allow_discard
{
    bool deprecated = true;
    return deprecated;
}
