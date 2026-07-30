from plot_config import PRETTY_MODEL_NAMES
from figures import PATH as FIGURES_PATH
from cache import PATH as CACHE_PATH
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

def generate_bar_chart_queries():
    data_real = {}
    data_synthetic = {}

    for file in CACHE_PATH.glob("*_sizes.csv"):
        df = pd.read_csv(file, sep=",", header=0, index_col=False)
        file_name = file.stem
        second_half = file_name.split(".owl")[1]
        model = second_half.split("_")[1]

        if " Total membership queries" not in df.columns or " Total equivalent queries" not in df.columns:
            continue  # ignora file malformati

        mem_mean = df[" Total membership queries"].mean()
        eq_mean = df[" Total equivalent queries"].mean()

        if "synthetic" in model.lower():
            data_synthetic[model] = (mem_mean, eq_mean)
        else:
            data_real[model] = (mem_mean, eq_mean)

    def plot(data_dict, filename):
        models = sorted(data_dict.keys())
        mem_values = [data_dict[m][0] for m in models]
        eq_values = [data_dict[m][1] for m in models]

        x = np.arange(len(models))
        width = 0.35

        fig, ax = plt.subplots(figsize=(8, 6))
        bars1 = ax.bar(x - width/2, mem_values, width, label='Membership queries', color='skyblue')
        bars2 = ax.bar(x + width/2, eq_values, width, label='Equivalent queries', color='salmon')

        ax.set_ylabel('Number of queries', fontsize=16)
        # ax.set_title('Average number of queries by model', fontsize=18, fontweight='bold')
        ax.set_xticks(x)
        ax.set_xticklabels(
            [PRETTY_MODEL_NAMES[m] for m in models],
            rotation=45,
            ha="right",
            fontsize=16,
        )
        ax.legend(fontsize=12)

        for bars in [bars1, bars2]:
            ax.bar_label(
                bars,
                fmt='%.0f',
                padding=3,
                fontsize=14,
                fontweight='bold',
                label_type='center'
            )

        plt.tight_layout()
        plt.savefig(FIGURES_PATH / f"{filename}.png", bbox_inches="tight")
        plt.savefig(FIGURES_PATH / f"{filename}.pdf", bbox_inches="tight")
        plt.savefig(FIGURES_PATH / f"{filename}.eps", bbox_inches="tight")
        plt.close(fig)

    if data_real:
        plot(data_real, "query_counts_by_model")
    if data_synthetic:
        plot(data_synthetic, "query_counts_by_model_synthetic")

if __name__ == "__main__":
    generate_bar_chart_queries()
