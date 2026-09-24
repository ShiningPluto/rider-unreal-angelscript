void Example()
{
    // The quote ends the f-string, as it does for the engine, which drops the unclosed
    // '{LaneName' from the message; left as an error, and the next line is unaffected
    devError(f"Bad InsertIndex {InsertIndex} when playing {RuntimeAsset.GetVoxAsset().Name} on lane {LaneName");
    FString After = f"lane {LaneName}";
}
