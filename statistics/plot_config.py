from pathlib import Path

PATH = Path(__file__).parents[0]


PRETTY_MODEL_NAMES = {
    "llama2:13b": "Llama2 (13b)",
    "llama2": "Llama2 (7b)",
    "llama3": "Llama3 (8b)",
    "llama3.1": "Llama3.1 (8b)",
    "llama3.1:70b": "Llama3.1 (70b)",
    "mistral": "Mistral (7b)",
    "mixtral": "Mixtral (47b)",
    "synthetic": "Synthetic",
    "false": "False"
}