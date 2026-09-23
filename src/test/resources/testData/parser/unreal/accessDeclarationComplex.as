class UAccessExample
{
    access Grants = protected, UExampleCapability (inherited, readonly), AHazePlayerCharacter, ExampleGlobalFunction(), * (editdefaults);

    access:Grants
    void Method()
    {
    }
}
