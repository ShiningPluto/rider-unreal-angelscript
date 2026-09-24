class AExample
{
    UPROPERTY(Meta = (UIMin = -48, UIMax = 0, ClampMin = -48, ClampMax = 0))
    float LoShelfMinGain = -6;

    UPROPERTY(Category = Inhale, meta=(UIMin = -10.0, UIMax = 0.0, Delta = 0.1, ForceUnits = "Seconds"))
    float RandomOffsetMin = 0.0;

    UPROPERTY(EditAnywhere, BlueprintReadWrite, Category = SpawnPattern, meta = (EditCondition = !bInfiniteSpawn, EditConditionHides))
    int MaxTotalSpawnedActors = 1;

    UPROPERTY(Category = LedgeGrab | Hang)
    float LedgePitchMaximum = 10.0;

    UPROPERTY(Category = Settings|Visuals, EditAnywhere)
    UNiagaraSystem VisualRopeAsset = nullptr;

    UPROPERTY(EditInstanceOnly, Category = "Drawable", Meta = (TitleProperty = {Event}))
    TArray<FSketchbookDrawablePropGroupAudioEventData> DrawableEvents;

    UFUNCTION(BlueprintEvent, Meta = ())
    void OnPredictionEnteredSyncPoint(FPinballRailSyncPointEventData EventData) {}
}
