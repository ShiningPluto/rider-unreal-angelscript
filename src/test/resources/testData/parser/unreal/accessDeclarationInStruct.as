struct FAccessExample
{
    access Internal =
        private,
        UExampleCapability (readonly);

    access:Internal
    float Value = 0.0;
}
