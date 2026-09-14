export function vehicleTitle(manufacturerName: string, modelName: string): string {
  return modelName.toLowerCase().startsWith(manufacturerName.toLowerCase())
    ? modelName
    : `${manufacturerName} ${modelName}`;
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Manufacturer {
  id: number;
  name: string;
  slug: string;
}

export interface VehicleModel {
  id: number;
  manufacturerId: number;
  modelName: string;
  slug: string;
}

export interface VehicleVariant {
  id: number;
  modelId: number;
  variantName: string;
  slug: string;
  yearStart: number | null;
  yearEnd: number | null;
  fuelType: string | null;
  driveType: string | null;
  gearbox: string | null;
  qualityStatus: string;
}

export interface Specs {
  powerHp: number | null;
  powerKw: number | null;
  powerBhp: number | null;
  torqueNm: number | null;
  torqueLbft: number | null;
  topSpeedKmh: number | null;
  acceleration0100KmhS: number | null;
  displacementCm3: number | null;
  weightKg: number | null;
  cylinderLayout: string | null;
  cylinderCount: number | null;
  co2EmissionsGKm: number | null;
  fuelConsumptionCombinedL100km: number | null;
}

export interface VariantDetail {
  variant: VehicleVariant;
  manufacturerName: string;
  modelName: string;
  specs: Specs;
  hasKnowledgeCoverage: boolean;
}

export interface CoveredVehicle {
  variantId: number;
  manufacturerName: string;
  modelName: string;
  variantName: string;
  yearStart: number | null;
  yearEnd: number | null;
}

export interface SessionSummary {
  id: number;
  title: string | null;
  variantId: number;
  manufacturerName: string;
  modelName: string;
  variantName: string;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: number;
  role: "user" | "assistant" | "system";
  content: string;
  structuredResponseJson: string | null;
  createdAt: string;
}

export interface SessionDetail {
  session: SessionSummary;
  messages: Message[];
}

export type AnswerType = "clarification" | "guidance" | "insufficient_evidence" | "safety_referral";

export interface Hypothesis {
  description: string;
  reasoning: string;
  evidenceChunkIds: number[];
}

export interface DiagnosticAnswer {
  answerType: AnswerType;
  summary: string;
  confirmedSymptoms: string[];
  followUpQuestions: string[];
  hypotheses: Hypothesis[];
  safeChecks: string[];
  cautions: string[];
  missingInformation: string[];
  sourceChunkIds: number[];
}

export interface EvidenceCard {
  chunkId: number;
  documentId: number;
  documentTitle: string;
  documentProvenance: string;
  documentSourceUrl: string | null;
  heading: string | null;
  sectionPath: string | null;
  pageNumber: number | null;
  excerpt: string;
  vectorScore: number | null;
  textScore: number | null;
  fusedScore: number;
}

export interface RagRunDebug {
  requestId: string;
  variantId: number;
  embeddingModel: string | null;
  generationModel: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  retrievalMillis: number | null;
  generationMillis: number | null;
  providerStatus: string;
  errorDetail: string | null;
}

export interface ChatTurnResult {
  messageId: number;
  answer: DiagnosticAnswer;
  evidence: EvidenceCard[];
  debug: RagRunDebug;
}

// ---- Motorcycle catalog, garage, and moto-chat types (Repair V2) ----
// Kept alongside the car types above rather than replacing them: the car
// catalogue/chat types remain valid for the preserved car prototype route.
// See docs/repair-v2-architecture.md.

export interface MotoManufacturer {
  id: number;
  name: string;
  slug: string;
}

export interface MotoModel {
  id: number;
  manufacturerId: number;
  name: string;
  slug: string;
}

export interface GarageVehicle {
  id: number;
  modelId: number;
  manufacturerName: string;
  modelName: string;
  year: number;
  market: string | null;
  nickname: string | null;
  currentOdometerKm: number | null;
  createdAt: string;
  updatedAt: string;
}

export function bikeTitle(vehicle: Pick<GarageVehicle, "manufacturerName" | "modelName">): string {
  return vehicle.modelName.toLowerCase().startsWith(vehicle.manufacturerName.toLowerCase())
    ? vehicle.modelName
    : `${vehicle.manufacturerName} ${vehicle.modelName}`;
}

export type MaintenanceServiceType =
  | "ENGINE_OIL_CHANGE"
  | "OIL_FILTER_CHANGE"
  | "SPARK_PLUG_CHANGE"
  | "AIR_FILTER_CHANGE"
  | "VALVE_CLEARANCE_CHECK"
  | "CHAIN_LUBE"
  | "CHAIN_ADJUSTMENT"
  | "BRAKE_FLUID_CHANGE"
  | "COOLANT_CHANGE"
  | "BATTERY_REPLACEMENT"
  | "TIRE_REPLACEMENT"
  | "OTHER";

export const SERVICE_TYPE_LABELS: Record<MaintenanceServiceType, string> = {
  ENGINE_OIL_CHANGE: "Engine oil",
  OIL_FILTER_CHANGE: "Oil filter",
  SPARK_PLUG_CHANGE: "Spark plugs",
  AIR_FILTER_CHANGE: "Air filter",
  VALVE_CLEARANCE_CHECK: "Valve clearance",
  CHAIN_LUBE: "Chain lubrication",
  CHAIN_ADJUSTMENT: "Chain adjustment",
  BRAKE_FLUID_CHANGE: "Brake fluid",
  COOLANT_CHANGE: "Coolant",
  BATTERY_REPLACEMENT: "Battery",
  TIRE_REPLACEMENT: "Tires",
  OTHER: "Other",
};

export interface MaintenanceEvent {
  id: number;
  garageVehicleId: number;
  serviceType: MaintenanceServiceType;
  odometerKm: number | null;
  performedAt: string | null;
  notes: string | null;
  createdVia: "chat" | "manual";
  createdAt: string;
}

export type MaintenanceStatus = "UNKNOWN" | "OK" | "DUE_SOON" | "DUE" | "OVERDUE";

export interface MaintenanceStatusCard {
  serviceType: MaintenanceServiceType;
  status: MaintenanceStatus;
  lastOdometerKm: number | null;
  lastPerformedAt: string | null;
  intervalKm: number | null;
  remainingKm: number | null;
  intervalMonths: number | null;
  remainingMonths: number | null;
  note: string | null;
}

export interface VehiclePreference {
  id: number;
  garageVehicleId: number;
  preferenceType: string;
  context: string;
  dataJson: string;
  createdAt: string;
  updatedAt: string;
}

export interface MotoSessionSummary {
  id: number;
  title: string | null;
  garageVehicleId: number;
  manufacturerName: string;
  modelName: string;
  year: number;
  createdAt: string;
  updatedAt: string;
}

export interface MotoMessage {
  id: number;
  role: "user" | "assistant" | "system";
  content: string;
  structuredResponseJson: string | null;
  createdAt: string;
}

export interface MotoSessionDetail {
  session: MotoSessionSummary;
  messages: MotoMessage[];
}

export type MotoAnswerType = "clarification" | "guidance" | "insufficient_evidence" | "safety_referral";

export interface MotoDiagnosticAnswer {
  answerType: MotoAnswerType;
  summary: string;
  confirmedFacts: string[];
  followUpQuestions: string[];
  safeChecks: string[];
  cautions: string[];
  sourceChunkIds: number[];
  proposedMaintenanceEvent: unknown;
  proposedOdometerUpdate: unknown;
  proposedPreference: unknown;
}

export interface MotoEvidenceCard {
  chunkId: number;
  documentId: number;
  section: string | null;
  subsection: string | null;
  category: string;
  heading: string | null;
  sectionPath: string | null;
  excerpt: string;
  vectorScore: number | null;
  textScore: number | null;
  fusedScore: number;
}

export interface MotoRagRunDebug {
  requestId: string;
  garageVehicleId: number;
  manufacturerName: string;
  modelName: string;
  year: number;
  embeddingModel: string | null;
  generationModel: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  retrievalMillis: number | null;
  generationMillis: number | null;
  providerStatus: string;
  errorDetail: string | null;
}

export interface ActionTaken {
  type: string;
  serviceType: MaintenanceServiceType | null;
  odometerKm: number | null;
  detail: string | null;
}

export interface MotoChatTurnResult {
  messageId: number;
  answer: MotoDiagnosticAnswer;
  evidence: MotoEvidenceCard[];
  actionsTaken: ActionTaken[];
  debug: MotoRagRunDebug;
}

export interface DataQualitySummary {
  manufacturers: number;
  models: number;
  variants: number;
  rawRecords: number;
  totalIssues: number;
  issuesByRule: { key: string; count: number }[];
  issuesBySeverity: { key: string; count: number }[];
}

export interface DataQualityIssue {
  id: number;
  rule: string;
  field: string | null;
  observedValuePreview: string | null;
  severity: string;
  explanation: string;
  manufacturerName: string | null;
  modelName: string | null;
  variantName: string | null;
}
